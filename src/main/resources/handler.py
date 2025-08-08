import json
import sys

import pymobiledevice3.usbmux as usbmux
from pymobiledevice3.usbmux import *
from pymobiledevice3.lockdown import create_using_usbmux

def list_devices(id, writer):
    devices = []
    for device in usbmux.list_devices():
        udid = device.serial

        lockdown = create_using_usbmux(udid, autopair=False, connection_type=device.connection_type)
        devices.append(lockdown.short_info)

    reply = {"id": id, "result": devices}

    print("Collected results: " + str(reply))

    writer.write(str(reply))
    writer.write("\n")
    writer.flush()


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


main()