import json
import queue
import sys
import threading
import traceback
from pathlib import Path
import asyncio

from threading import Thread
from packaging.version import Version

import pymobiledevice3.usbmux as usbmux
from pymobiledevice3.exceptions import *
from pymobiledevice3.services.installation_proxy import InstallationProxyService
from pymobiledevice3.services.mobile_image_mounter import auto_mount
from pymobiledevice3.tcp_forwarder import LockdownTcpForwarder, UsbmuxTcpForwarder
from pymobiledevice3.tunneld.api import get_tunneld_device_by_udid
from pymobiledevice3.usbmux import *
from pymobiledevice3.lockdown import create_using_usbmux
import plistlib

active_debug_server: dict[int, tuple[LockdownTcpForwarder, Thread]] = {}
active_usbmux_forwarder: dict[int, tuple[UsbmuxTcpForwarder, Thread]] = {}

class WriteDispatcher:
    def __init__(self, writer):
        self.writer = writer
        self.write_queue = queue.Queue()
        self.shutdown_event = threading.Event()
        self.write_thread = threading.Thread(target=self._write_worker, daemon=True)
        self.write_thread.start()

    def _write_worker(self):
        try:
            while True:
                try:
                    message = self.write_queue.get()
                    if message is None:
                        break

                    self.writer.write(str(message))
                    self.writer.write("\n")
                    self.writer.flush()

                except Exception as e:
                    print(f"Write thread error: {e}")
                    break
        finally:
            self.shutdown_event.set()

    def write_reply(self, reply: dict):
        if self.shutdown_event.is_set():
            return
        print("Sending packet: " + str(reply))
        self.write_queue.put(reply)

    def shutdown(self):
        self.write_queue.put(None)
        self.write_thread.join()


def list_devices(id, writer):
    devices = []
    for device in usbmux.list_devices():
        udid = device.serial

        with create_using_usbmux(udid, autopair=False, connection_type=device.connection_type) as lockdown:
            devices.append(lockdown.short_info)

    reply = {"id": id, "state": "completed", "result": devices}

    print("Collected results: " + str(reply))

    writer.write_reply(reply)

def list_devices_udid(id, writer):
    devices = []
    for device in usbmux.list_devices():
        devices.append(device.serial)

    reply = {"id": id, "state": "completed", "result": devices}

    print("Collected results: " + str(reply))

    writer.write_reply(reply)

def get_device(id, device_id, writer):
    try:
        with create_using_usbmux(device_id, autopair=False) as lockdown:
            reply = {"id": id, "state": "completed", "result": lockdown.short_info}
            writer.write_reply(reply)
    except NoDeviceConnectedError | DeviceNotFoundError:
        reply = {"id": id, "state": "failed_expected"}
        writer.write_reply(reply)

def install_app(id, lockdown_client, path, mode, writer):
    with InstallationProxyService(lockdown=lockdown_client) as installer:
        options = {"PackageType": "Developer"}

        def progress_handler(progress, *args):
            reply = {"id": id, "state": "progress", "progress": progress}
            writer.write_reply(reply)
            return

        if mode == "INSTALL":
            installer.install(path, options=options, handler=progress_handler)
        elif mode == "UPGRADE":
            installer.upgrade(path, options=options, handler=progress_handler)
        else:
            raise RuntimeError(f"Invalid mode [{mode}]")

        info_plist_path = Path(path) / "Info.plist"

        with open(info_plist_path, 'rb') as f:
            plist_data = plistlib.load(f)

        bundle_identifier = plist_data["CFBundleIdentifier"]

        res = installer.lookup(options={"BundleIDs" : [bundle_identifier]})

        reply = {"id": id, "state": "completed", "result": res[bundle_identifier]["Path"]}
        writer.write_reply(reply)

        print("Installed bundle: " + str(bundle_identifier))

def decode_plist(id, path, writer):
    with open(path, 'rb') as f:
        plist_data = plistlib.load(f)
        reply = {"id": id, "state": "completed", "result": plist_data}
        writer.write_reply(reply)
        return

def auto_mount_image(id, lockdown, writer):
    try:
        asyncio.run(auto_mount(lockdown))
    except AlreadyMountedError:
        pass
    reply = {"id": id, "state": "completed"}
    writer.write_reply(reply)


def debugserver_connect(id, lockdown, port, writer):
    try:
        discovery_service = get_tunneld_device_by_udid(lockdown.udid)
        if not discovery_service:
            raise TunneldConnectionError()
    except TunneldConnectionError:
        reply = {"id":id, "state": "failed_tunneld"}
        writer.write_reply(reply)
        return

    if Version(discovery_service.product_version) < Version('17.0'):
        service_name = 'com.apple.debugserver.DVTSecureSocketProxy'
    else:
        service_name = 'com.apple.internal.dt.remote.debugproxy'
    listen_event = threading.Event()
    forwarder = LockdownTcpForwarder(discovery_service, port, service_name, listening_event=listen_event)

    def forwarder_thread():
        forwarder.start()

    thread = threading.Thread(target=forwarder_thread, daemon=True)
    thread.start()

    listen_event.wait()
    selected_port = forwarder.server_socket.getsockname()[1]

    active_debug_server[selected_port] = (forwarder, thread)

    reply = {"id": id, "state": "completed", "result": {
        "host": "127.0.0.1",
        "port": selected_port
    }}
    writer.write_reply(reply)


def debugserver_close(id, port, writer):
    forwarder, thread = active_debug_server.pop(port)
    forwarder.stop()

    thread.join(5)

    if thread.is_alive():
        print(f"Joining debugserver thread {port} timed out")

    reply = {"id": id, "state": "completed"}
    writer.write_reply(reply)


def usbmux_forward_open(id, udid, remote_port, local_port, writer):
    listen_event = threading.Event()

    forwarder = UsbmuxTcpForwarder(udid, remote_port, local_port, listening_event=listen_event)

    def forwarder_thread():
        forwarder.start()

    thread = threading.Thread(target=forwarder_thread, daemon=True)
    thread.start()

    listen_event.wait()
    selected_port = forwarder.server_socket.getsockname()[1]

    active_usbmux_forwarder[selected_port] = (forwarder, thread)

    reply = {"id": id, "state": "completed", "result": {
        "host": "127.0.0.1",
        "local_port": selected_port,
        "remote_port": remote_port
    }}
    writer.write_reply(reply)


def usbmux_forward_close(id, local_port, writer):
    forwarder, thread = active_usbmux_forwarder.pop(local_port)
    forwarder.stop()

    thread.join(5)

    if thread.is_alive():
        print(f"Joining usbmux thread {local_port} timed out")

    reply = {"id": id, "state": "completed"}
    writer.write_reply(reply)


def main():
    if len(sys.argv) < 2:
        print("Usage: python handler.py <port>")
        sys.exit(1)

    port = int(sys.argv[1])

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect(('localhost', port))

    reader = sock.makefile('r')
    writer = sock.makefile('w')

    write_dispatcher = WriteDispatcher(writer)

    while True:
        command = reader.readline().strip()
        if not command:
            break
        print("Received command: {}".format(command))

        try:
            res = json.loads(command)
            id = res['id']

            command_type = res['command']
            if command_type == "exit":
                write_dispatcher.shutdown()
                reader.close()
                writer.close()
                sock.close()
                sys.exit(0)
            elif command_type == "list_devices":
                list_devices(id, write_dispatcher)
                continue
            elif command_type == "list_devices_udid":
                list_devices_udid(id, write_dispatcher)
                continue
            elif command_type == "get_device":
                device_id = res['device_id'] if 'device_id' in res else None
                get_device(id, device_id, write_dispatcher)
                continue
            elif command_type == "decode_plist":
                decode_plist(id, res['plist_path'], write_dispatcher)
                continue
            elif command_type == "debugserver_close":
                debugserver_close(id, res['port'], write_dispatcher)
                continue
            elif command_type == "usbmux_forwarder_open":
                usbmux_forward_open(id, res['device_id'], res['remote_port'], res['local_port'], write_dispatcher)
                continue
            elif command_type == "usbmux_forwarder_close":
                usbmux_forward_close(id, res['local_port'], write_dispatcher)
                continue

            # Now come the device targetted functions
            device_id = res['device_id']
            with create_using_usbmux(device_id) as lockdown:
                if command_type == "install_app":
                    install_app(id, lockdown, res['app_path'], res['install_mode'], write_dispatcher)
                    continue
                elif command_type == "auto_mount_image":
                    auto_mount_image(id, lockdown, write_dispatcher)
                    continue
                elif command_type == "debugserver_connect":
                    port = res['port'] if 'port' in res else 0
                    debugserver_connect(id, lockdown, port, write_dispatcher)
                    continue

        except Exception as e:
            reply = {"request": command, "state": "failed", "error": repr(e), "backtrace": traceback.format_exc()}
            write_dispatcher.write_reply(reply)

main()