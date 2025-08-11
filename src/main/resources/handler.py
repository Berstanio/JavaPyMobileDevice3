import json
import sys
from pathlib import Path

import pymobiledevice3.usbmux as usbmux
from pymobiledevice3.services.installation_proxy import InstallationProxyService
from pymobiledevice3.usbmux import *
from pymobiledevice3.lockdown import create_using_usbmux

def list_devices(id, writer):
    devices = []
    for device in usbmux.list_devices():
        udid = device.serial

        with create_using_usbmux(udid, autopair=False, connection_type=device.connection_type) as lockdown:
            devices.append(lockdown.short_info)

    reply = {"id": id, "result": devices}

    print("Collected results: " + str(reply))

    writer.write(str(reply))
    writer.write("\n")
    writer.flush()


def install_app(id, lockdown_client, path, mode, writer):
    with InstallationProxyService(lockdown=lockdown_client) as installer:
        options = {"PackageType": "Developer"}

        def progress_handler(progress, *args):
            reply = {"id": id, "progress": progress}
            writer.write(str(reply))
            writer.write("\n")
            writer.flush()
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

        reply = {"id": id, "result": res[bundle_identifier]["Path"]}
        writer.write(str(reply))
        writer.write("\n")
        writer.flush()

        print("Installed bundle: " + str(bundle_identifier))

def main():
    if len(sys.argv) < 2:
        print("Usage: python handler.py <port>")
        sys.exit(1)

    port = int(sys.argv[1])

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect(('localhost', port))

    reader = sock.makefile('r')
    writer = sock.makefile('w')

    while True:
        command = reader.readline().strip()
        if not command:
            break
        print("Received command: {}".format(command))

        res = json.loads(command)
        id = res['id']
        command_type = res['command']

        if command_type == "exit":
            reader.close()
            writer.close()
            sock.close()
            sys.exit(0)
        elif command_type == "list_devices":
            list_devices(id, writer)

        # Now come the device targetted functions
        device_id = res['device_id'] if 'device_id' in res else None
        with create_using_usbmux(device_id) as lockdown:
            if command_type == "install_app":
                install_app(id, lockdown, res['path'], res['mode'], writer)

main()