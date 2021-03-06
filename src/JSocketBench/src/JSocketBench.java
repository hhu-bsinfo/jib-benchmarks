import java.io.*;
import java.net.URL;
import java.nio.file.Files;

/**
 * The main class.
 *
 * Contains all configuration variables and starts the benchmark.
 *
 * @author Fabian Ruhland, HHU
 * @date 2018
 */
public class JSocketBench {

    /**
     * The connection mode (server or client).
     */
    private MODE mode = null;

    /**
     * The remote host's hostname (only relevant in client mode).
     */
    private String remoteHostname = null;

    /**
     * The address to bind the local socket to.
     */
    private String bindAddress = null;

    /**
     * The benchmark to be executed (unidirectional, bidirectional or pingpong).
     */
    private BENCHMARK benchmark = BENCHMARK.UNIDIRECTIONAL;

    /**
     * The buffer size to be used for the messages that are sent/received.
     */
    private int bufSize = 1024;

    /**
     * The amount of messages to send/receive.
     */
    private long messageCount = 1000000;

    /**
     * The TCP-port to be used for the connection.
     */
    private int port = 8888;

    /**
     * Infiniband performance counters.
     */
    private IbPerfCounter perfCounter = null;

    /**
     * The performance counter mode.
     */
    private PERF_COUNTER_MODE perfCounterMode = PERF_COUNTER_MODE.OFF;

    /**
     * The connection.
     */
    private Connection connection = null;

    /**
     * Contains all benchmarks.
     */
    private Benchmarks benchmarks = null;

    /**
     * Possible connections modes (server or client).
     */
    private enum MODE {
        SERVER, /**< Run as server **/
        CLIENT  /**< Run as client */
    }

    /**
     * Possible benchmarks (unidirectional, bidirectional or pingpong).
     */
    private enum BENCHMARK {
        UNIDIRECTIONAL, /**< Run a unidirectional benchmark with one sender and one receiver */
        BIDIRECTIONAL,  /**< Run a bidirectional benchmark, where both hosts send and receive simaltaneously */
        PINGPONG        /**< Run a pingpong benchmark */
    }

    /**
     * Possible perf counter modes (off, compat or mad).
     */
    private enum PERF_COUNTER_MODE {
        OFF,    /**< Do not measure raw statistics */
        COMPAT, /**< Use the filesystem to measure raw statistics */
        MAD     /**< Use the ibmad-library to measure raw statistics */
    }

    /**
     * Constructor.
     *
     * Parses the arguments and sets the configuration variables accordingly.
     *
     * @param args The arguments.
     */
    private JSocketBench(String[] args) {
        for(int i = 0; i < args.length; i++) {
            if (i == args.length - 1) {
                printUsage();
                Log.ERROR_AND_EXIT("MAIN", "Unable to parse options!");
            }

            switch (args[i]) {
                case "-m":
                case "--mode":
                    String mode = args[++i];

                    switch (mode) {
                        case "server":
                            this.mode = MODE.SERVER;
                            break;
                        case "client":
                            this.mode = MODE.CLIENT;
                            break;
                        default:
                            Log.ERROR_AND_EXIT("MAIN","Invalid mode '%s'!", mode);
                    }
                    break;
                case "-r":
                case "--remote":
                    this.remoteHostname = args[++i];
                    break;
                case "-a":
                case "--address":
                    this.bindAddress = args[++i];
                    break;
                case "-b":
                case "--benchmark":
                    String benchmark = args[++i];

                    switch (benchmark) {
                        case "unidirectional":
                            this.benchmark = BENCHMARK.UNIDIRECTIONAL;
                            break;
                        case "bidirectional":
                            this.benchmark = BENCHMARK.BIDIRECTIONAL;
                            break;
                        case "pingpong":
                            this.benchmark = BENCHMARK.PINGPONG;
                            break;
                        default:
                            Log.ERROR_AND_EXIT("MAIN","Invalid benchmark '%s'!", benchmark);
                    }
                    break;
                case "-s":
                case "--size":
                    this.bufSize = Integer.parseUnsignedInt(args[++i]);
                    break;
                case "-c":
                case "--count":
                    this.messageCount = Long.parseUnsignedLong(args[++i]);
                    break;
                case "-p":
                case "--port":
                    this.port = Integer.parseUnsignedInt(args[++i]);
                    break;
                case "-rs":
                case "--raw-statistics":
                    String perfCounterMode = args[++i];

                    switch (perfCounterMode) {
                        case "off":
                            this.perfCounterMode = PERF_COUNTER_MODE.OFF;
                            break;
                        case "compat":
                            this.perfCounterMode = PERF_COUNTER_MODE.COMPAT;
                            break;
                        case "mad":
                            this.perfCounterMode = PERF_COUNTER_MODE.MAD;
                            break;
                        default:
                            Log.ERROR_AND_EXIT("MAIN","Invalid perf counter mode '%s'!", perfCounterMode);
                    }
                    break;
                case "-v":
                case "--verbosity":
                    Log.VERBOSITY = Integer.parseUnsignedInt(args[++i]);
                    break;
            }
        }

        if(this.mode == MODE.CLIENT) {
            this.perfCounterMode = PERF_COUNTER_MODE.OFF;
        }

        this.benchmarks = new Benchmarks();
    }

    /**
     * Execute the specified benchmark in a separate thread (or two, when bidirectional is chosen).
     */
    private void run() {
        Thread sendThread;
        Thread recvThread;

        if(mode == null || (mode == MODE.CLIENT && remoteHostname == null)) {
            printUsage();
            Log.ERROR_AND_EXIT("MAIN", "Missing required parameters!");
        }

        connection = new Connection(bufSize);

        if(mode == MODE.SERVER) {
            connection.connectToClient(bindAddress, port);
        } else {
            connection.connectToServer(bindAddress, remoteHostname, port);
        }

        if(perfCounterMode == PERF_COUNTER_MODE.COMPAT) {
            perfCounter = new IbPerfCounter(true);
            perfCounter.resetCounters();
        } else if(perfCounterMode == PERF_COUNTER_MODE.MAD) {
            perfCounter = new IbPerfCounter(false);
            perfCounter.resetCounters();
        }

        if(mode == MODE.SERVER && benchmark == BENCHMARK.UNIDIRECTIONAL) {
            sendThread = new Thread(() -> { benchmarks.sendBenchmark(connection, messageCount); } );
            sendThread.start();

            try {
                sendThread.join();
            } catch (InterruptedException e) {
                Log.ERROR_AND_EXIT("MAIN", "A thread has been interrupted unexpectedly! Error: %s",
                        e.getMessage());
            }
        } else if(mode == MODE.CLIENT && benchmark == BENCHMARK.UNIDIRECTIONAL) {
            recvThread = new Thread(() -> { benchmarks.recvBenchmark(connection, messageCount); } );
            recvThread.start();

            try {
                recvThread.join();
            } catch (InterruptedException e) {
                Log.ERROR_AND_EXIT("MAIN", "A thread has been interrupted unexpectedly! Error: %s",
                        e.getMessage());
            }
        } else if(benchmark == BENCHMARK.BIDIRECTIONAL) {
            sendThread = new Thread(() -> { benchmarks.sendBenchmark(connection, messageCount); } );
            recvThread = new Thread(() -> { benchmarks.recvBenchmark(connection, messageCount); } );

            sendThread.start();
            recvThread.start();

            try {
                sendThread.join();
                recvThread.join();
            } catch (InterruptedException e) {
                Log.ERROR_AND_EXIT("MAIN", "A thread has been interrupted unexpectedly! Error: %s",
                        e.getMessage());
            }
        } else if(benchmark == BENCHMARK.PINGPONG) {
            if(mode == MODE.SERVER) {
                sendThread = new Thread(() -> { benchmarks.pingPongBenchmarkServer(connection, messageCount); } );
            } else {
                sendThread = new Thread(() -> { benchmarks.pingPongBenchmarkClient(connection, messageCount); } );
            }

            sendThread.start();

            try {
                sendThread.join();
            } catch (InterruptedException e) {
                Log.ERROR_AND_EXIT("MAIN", "A thread has been interrupted unexpectedly! Error: %s",
                        e.getMessage());
            }
        }

        if(perfCounterMode != PERF_COUNTER_MODE.OFF) {
            perfCounter.refreshCounters();
        }

        connection.close();

        if(mode == MODE.SERVER) {
            printResults();
        } else {
            System.out.println("See results on server!");
        }

        benchmarks = new Benchmarks();
    }

    /**
     * Print the help message.
     */
    private static void printUsage() {
        System.out.print("Usage: java -jar JSocketBench.jar [OPTION...]\n" +
                "Available options:\n" +
                "-m, --mode\n" +
                "    Set the operating mode (server/client). This is a required option!\n" +
                "-r, --remote\n" +
                "    Set the remote hostname. This is a required option when the program is running as a client!\n" +
                "-a, --address\n" +
                "    Set the address to bind the local socket to.\n" +
                "-b, --benchmark\n" +
                "    Set the benchmark to be executed. Available benchmarks are: " +
                "'unidirectional', 'bidirectional' and 'pingpong' (Default: 'unidirectional').\n" +
                "-s, --size\n" +
                "    Set the message size in bytes (Default: 1024).\n" +
                "-c, --count\n" +
                "    Set the amount of messages to be sent (Default: 1000000).\n" +
                "-p, --port\n" +
                "    Set the TCP-port to be used for the connection (Default: 8888).\n" +
                "-rs, --raw-statistics\n" +
                "    Show infiniband perfomance counters\n" +
                "        'mad'    = Use libibmad to get performance counters (requires root-privileges!)\n" +
                "        'compat' = Use filesystem to get performance counters\n" +
                "        'off'    = Don't show performance counters (Default).\n" +
                "-v, --verbosity\n" +
                "    Set the verbosity level: 0 = Fatal errors and raw results,\n" +
                "                             1 = Fatal errors formatted results,\n" +
                "                             2 = All errors and formatted results,\n" +
                "                             3 = All errors/warnings and formatted results,\n" +
                "                             4 = All log messages and formatted results (Default).\n");
    }

    /**
     * Print the benchmark results.
     */
    private void printResults() {
        long sendTime = benchmarks.getSendTime();
        long recvTime = benchmarks.getRecvTime();

        if(benchmark == BENCHMARK.PINGPONG) {
            long avgLatency = sendTime / messageCount;

            if(Log.VERBOSITY > 0) {
                System.out.print("Results:\n");
                System.out.printf("  Total time: %.2f s\n", sendTime / ((double) 1000000000));
                System.out.printf("  Average request response latency: %.2f us\n", avgLatency /
                        (double) 1000);
            } else {
                System.out.printf("%f\n", sendTime / ((double) 1000000000));
                System.out.printf("%f\n", avgLatency / (double) 1000);
            }
        } else {
            long totalData = messageCount * bufSize;

            double sendPktsRate = (messageCount / (sendTime / ((double) 1000000000)) / ((double) 1000));

            double recvPktsRate = recvTime == 0 ? 0 : (messageCount / (recvTime / ((double) 1000000000)) /
                    ((double) 1000));

            double sendAvgThroughputMib = totalData /
                    (sendTime / ((double) 1000000000)) / ((double) 1024) / ((double) 1024);

            double sendAvgThroughputMb = totalData /
                    (sendTime / ((double) 1000000000)) / ((double) 1000) / ((double) 1000);

            double recvAvgThroughputMib = recvTime == 0 ? 0 : totalData /
                    (recvTime / ((double) 1000000000)) / ((double) 1024) / ((double) 1024);

            double recvAvgThroughputMb = recvTime == 0 ? 0 : totalData /
                    (recvTime / ((double) 1000000000)) / ((double) 1000) / ((double) 1000);

            double sendAvgLatency = sendTime / (double) messageCount / (double) 1000;

            // Even if we only send data, a few bytes will also be received, because of the RC-protocol,
            // so if recvTime is 0, we just set it to sendTime,
            // so that the raw receive throughput can be calculated correctly.
            if (recvTime == 0) {
                recvTime = sendTime;
            } else if (sendTime == 0) {
                recvTime = 0;
            }

            double sendAvgRawThroughputMib = perfCounterMode == PERF_COUNTER_MODE.OFF ? 0 :
                    perfCounter.getXmitDataBytes() / (sendTime / ((double) 1000000000)) /
                            ((double) 1024) / ((double) 1024);

            double sendAvgRawThroughputMb = perfCounterMode == PERF_COUNTER_MODE.OFF ? 0 :
                    perfCounter.getXmitDataBytes() / (sendTime / ((double) 1000000000)) /
                            ((double) 1000) / ((double) 1000);

            double recvAvgRawThroughputMib = perfCounterMode == PERF_COUNTER_MODE.OFF ? 0 :
                    perfCounter.getRcvDataBytes() /(recvTime / ((double) 1000000000)) /
                            ((double) 1024) / ((double) 1024);

            double recvAvgRawThroughputMb = perfCounterMode == PERF_COUNTER_MODE.OFF ? 0 :
                    perfCounter.getRcvDataBytes() / (recvTime / ((double) 1000000000)) /
                            ((double) 1000) / ((double) 1000);

            double sendOverhead = 0;
            double recvOverhead = 0;

            if(perfCounterMode != PERF_COUNTER_MODE.OFF && totalData < perfCounter.getXmitDataBytes()) {
                sendOverhead = perfCounter.getXmitDataBytes() - totalData;
            }

            if(perfCounterMode != PERF_COUNTER_MODE.OFF && totalData < perfCounter.getRcvDataBytes()) {
                recvOverhead = perfCounter.getRcvDataBytes() - totalData;
            }

            double sendOverheadPercentage = sendOverhead / (double) totalData;
            double recvOverheadPercentage = recvOverhead / (double) totalData;

            if (Log.VERBOSITY > 0) {
                System.out.print("Results:\n");
                System.out.printf("  Total time: %.2f s\n", sendTime / ((double) 1000000000));
                System.out.printf("  Total data: %.2f MiB (%.2f MB)\n",
                        totalData / ((double) 1024) / ((double) 1024),
                        totalData / ((double) 1000) / ((double) 1000));
                System.out.printf("  Average sent packet per second: %.2f kPkts/s\n",
                        sendPktsRate);
                System.out.printf("  Average recv packet per second: %.2f kPkts/s\n",
                        recvPktsRate);
                System.out.printf("  Average combined packet per second: %.2f kPkts/s\n",
                        recvPktsRate);
                System.out.printf("  Average send throughput:     %.2f MiB/s (%.2f MB/s)\n",
                        sendAvgThroughputMib, sendAvgThroughputMb);
                System.out.printf("  Average recv throughput:     %.2f MiB/s (%.2f MB/s)\n",
                        recvAvgThroughputMib, recvAvgThroughputMb);
                System.out.printf("  Average combined throughput: %.2f MiB/s (%.2f MB/s)\n",
                        sendAvgThroughputMib + recvAvgThroughputMib, sendAvgThroughputMb + recvAvgThroughputMb);
                System.out.printf("  Average send latency: %.2f us\n", sendAvgLatency);

                if(perfCounterMode != PERF_COUNTER_MODE.OFF) {
                    System.out.print("\nRaw statistics:\n");
                    System.out.printf("  Total packets sent: %d\n", perfCounter.getXmitPkts());
                    System.out.printf("  Total packets received %d\n", perfCounter.getRcvPkts());
                    System.out.printf("  Total data sent: %.2f MiB (%.2f MB)\n", perfCounter.getXmitDataBytes() /
                                    ((double) 1024) / ((double) 1024),
                            perfCounter.getXmitDataBytes() / ((double) 1000) / ((double) 1000));
                    System.out.printf("  Total data received: %.2f MiB (%.2f MB)\n", perfCounter.getRcvDataBytes() /
                                    ((double) 1024) / ((double) 1024),
                            perfCounter.getRcvDataBytes() / ((double) 1000) / ((double) 1000));
                    System.out.printf("  Send overhead: %.2f MiB (%.2f MB), %.2f%%\n", sendOverhead /
                                    ((double) 1024) / ((double) 1024),
                            sendOverhead / ((double) 1000) / ((double) 1000), sendOverheadPercentage * 100);
                    System.out.printf("  Receive overhead: %.2f MiB (%.2f MB), %.2f%%\n", recvOverhead /
                                    ((double) 1024) / ((double) 1024),
                            recvOverhead / ((double) 1000) / ((double) 1000), recvOverheadPercentage * 100);
                    System.out.printf("  Total sent data: %.2f MiB (%.2f MB)\n",
                            perfCounter.getXmitDataBytes() / ((double) 1024) / ((double) 1024),
                            perfCounter.getXmitDataBytes() / ((double) 1000) / ((double) 1000));
                    System.out.printf("  Total received data: %.2f MiB (%.2f MB)\n",
                            perfCounter.getXmitDataBytes() / ((double) 1024) / ((double) 1024),
                            perfCounter.getXmitDataBytes() / ((double) 1000) / ((double) 1000));
                    System.out.printf("  Average send throughput:     %.2f MiB/s (%.2f MB/s)\n",
                            sendAvgRawThroughputMib, sendAvgRawThroughputMb);
                    System.out.printf("  Average recv throughput:     %.2f MiB/s (%.2f MB/s)\n",
                            recvAvgRawThroughputMib, recvAvgRawThroughputMb);
                    System.out.printf("  Average combined throughput: %.2f MiB/s (%.2f MB/s)\n",
                            sendAvgRawThroughputMib + recvAvgRawThroughputMib,
                            sendAvgRawThroughputMb + recvAvgRawThroughputMb);
                }
            } else {
                System.out.printf("%f\n", sendTime / ((double) 1000000000));
                System.out.printf("%f\n", totalData / ((double) 1024) / ((double) 1024));
                System.out.printf("%f\n", sendPktsRate);
                System.out.printf("%f\n", recvPktsRate);
                System.out.printf("%f\n", sendPktsRate + recvPktsRate);
                System.out.printf("%f\n", sendAvgThroughputMb);
                System.out.printf("%f\n", recvAvgThroughputMb);
                System.out.printf("%f\n", sendAvgThroughputMb + recvAvgThroughputMb);
                System.out.printf("%f\n", sendAvgLatency);

                if(perfCounterMode != PERF_COUNTER_MODE.OFF) {
                    System.out.printf("%d\n", perfCounter.getXmitPkts());
                    System.out.printf("%d\n", perfCounter.getXmitPkts());
                    System.out.printf("%f\n", perfCounter.getXmitDataBytes() / ((double) 1024) / ((double) 1024));
                    System.out.printf("%f\n", perfCounter.getRcvDataBytes() / ((double) 1024) / ((double) 1024));
                    System.out.printf("%f\n", sendOverhead / ((double) 1024) / ((double) 1024) );
                    System.out.printf("%f\n", sendOverheadPercentage * 100);
                    System.out.printf("%f\n", recvOverhead / ((double) 1024) / ((double) 1024) );
                    System.out.printf("%f\n", recvOverheadPercentage * 100);
                    System.out.printf("%f\n", sendAvgRawThroughputMb);
                    System.out.printf("%f\n", recvAvgRawThroughputMb);
                    System.out.printf("%f\n", sendAvgRawThroughputMb + recvAvgRawThroughputMb);
                }
            }
        }
    }

    /**
     * Load a native library, that is contained inside the .jar-file.
     *
     * Based on https://stackoverflow.com/a/49500154
     *
     * @param path The library's path inside tje .jar-file
     */
    private static void loadNativeLibraryFromJar(String path) throws IOException {
        File tmpDir = Files.createTempDirectory("JSocketBench-native").toFile();
        tmpDir.deleteOnExit();

        File nativeLibTmpFile = new File(tmpDir, path);
        nativeLibTmpFile.deleteOnExit();

        URL url = JSocketBench.class.getResource(path);
        InputStream in = url.openStream();

        Files.copy(in, nativeLibTmpFile.toPath());
        System.load(nativeLibTmpFile.getAbsolutePath());
    }

    /**
     * The main-method.
     */
    public static void main(String[] args) {
        JSocketBench bench = new JSocketBench(args);

        try {
            Process process = Runtime.getRuntime().exec("id -u");
            String output = new BufferedReader(new InputStreamReader(process.getInputStream())).readLine();

            int uid = Integer.parseInt(output);

            if(uid != 0) {
                Log.WARN("MAIN", "Not running with root privileges. " +
                        "If any errors occur, try restarting as root!");
            }
        } catch (Exception e) {
            Log.WARN("MAIN", "Unable to determine user id! Error: %s", e.getMessage());
        }

        if(bench.perfCounterMode != PERF_COUNTER_MODE.OFF) {
            try {
                loadNativeLibraryFromJar("/libIbPerfCounter.so");
                Log.INFO("MAIN", "Successfully loaded native library 'libIbPerfCounter.so'");
            } catch (Exception e) {
                Log.ERROR_AND_EXIT("MAIN", "Unable to load native library 'libIbPerfCounter.so'! Error: %s",
                        e.getMessage());
            }
        }

        bench.run();
    }
}
