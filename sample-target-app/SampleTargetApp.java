import com.sun.management.OperatingSystemMXBean;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Stand-in for the application under test: exposes real JVM heap and CPU figures the way Spring Boot Actuator
 * and Quarkus (Micrometer/Prometheus) do, and generates a slow wave of CPU and memory load so graphs have a shape.
 *
 *   java -Xmx512m sample-target-app/SampleTargetApp.java [port] [--no-load]
 *
 *   Spring Boot style  http://localhost:8080/actuator/metrics   (report.metrics.format=actuator)
 *   Quarkus style      http://localhost:8080/q/metrics          (report.metrics.format=prometheus)
 */
public class SampleTargetApp {

    private static final OperatingSystemMXBean OS = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final List<byte[]> RETAINED = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 && !args[0].startsWith("--") ? Integer.parseInt(args[0]) : 8080;
        boolean load = !List.of(args).contains("--no-load");

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/actuator/health", exchange -> reply(exchange, "application/json", "{\"status\":\"UP\"}"));
        server.createContext("/actuator/metrics/jvm.memory.used", exchange -> reply(exchange, "application/json", measurement("jvm.memory.used", heap().getUsed())));
        server.createContext("/actuator/metrics/jvm.memory.max", exchange -> reply(exchange, "application/json", measurement("jvm.memory.max", heap().getMax())));
        server.createContext("/actuator/metrics/process.cpu.usage", exchange -> reply(exchange, "application/json", measurement("process.cpu.usage", cpu())));
        server.createContext("/q/metrics", exchange -> reply(exchange, "text/plain", prometheus()));
        server.start();
        System.out.println("Sample target app on port " + port + (load ? " (generating load)" : ""));

        if (load) {
            generateLoad();
        }
    }

    private static MemoryUsage heap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    }

    private static double cpu() {
        return Math.max(OS.getProcessCpuLoad(), 0);
    }

    private static String measurement(String name, double value) {
        return "{\"name\":\"" + name + "\",\"measurements\":[{\"statistic\":\"VALUE\",\"value\":" + value + "}],\"availableTags\":[]}";
    }

    private static String prometheus() {
        return "# TYPE jvm_memory_used_bytes gauge\n"
                + "jvm_memory_used_bytes{area=\"heap\",id=\"demo\"} " + (double) heap().getUsed() + "\n"
                + "jvm_memory_max_bytes{area=\"heap\",id=\"demo\"} " + (double) heap().getMax() + "\n"
                + "process_cpu_usage " + cpu() + "\n";
    }

    private static void reply(HttpExchange exchange, String type, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** 20 s cycle: heap grows to ~300 MB then is released; one busy thread per core follows a sine-shaped duty cycle. */
    private static void generateLoad() throws InterruptedException {
        for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
            Thread worker = new Thread(SampleTargetApp::burnCpu, "load-" + i);
            worker.setDaemon(true);
            worker.start();
        }
        for (long tick = 0; ; tick++) {
            double phase = (tick % 200) / 200.0;
            synchronized (RETAINED) {
                if (phase < 0.6 && RETAINED.size() < 75) {
                    RETAINED.add(new byte[4 * 1024 * 1024]);
                } else if (phase >= 0.6) {
                    RETAINED.clear();
                }
            }
            Thread.sleep(100);
        }
    }

    private static void burnCpu() {
        for (long tick = 0; ; tick++) {
            double duty = 0.05 + 0.85 * Math.max(0, Math.sin((tick % 200) / 200.0 * 2 * Math.PI));
            long busyUntil = System.nanoTime() + (long) (duty * 100_000_000L);
            while (System.nanoTime() < busyUntil) {
                Math.sqrt(System.nanoTime());
            }
            try {
                Thread.sleep((long) ((1 - duty) * 100) + 1);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
