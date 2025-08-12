import json
import queue
import sys
import threading
import traceback
from pathlib import Path

import pymobiledevice3.usbmux as usbmux
from pymobiledevice3.exceptions import *
from pymobiledevice3.services.installation_proxy import InstallationProxyService
from pymobiledevice3.usbmux import *
from pymobiledevice3.lockdown import create_using_usbmux
import plistlib

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

            # Now come the device targetted functions
            device_id = res['device_id'] if 'device_id' in res else None
            with create_using_usbmux(device_id) as lockdown:
                if command_type == "install_app":
                    install_app(id, lockdown, res['app_path'], res['install_mode'], write_dispatcher)
        except Exception as e:
            reply = {"request": command, "state": "failed", "error": repr(e), "backtrace": traceback.format_exc()}
            write_dispatcher.write_reply(reply)

main()