// jvmlens criterion-#1 test workload — dependency-free, single-file.
// Run with single-file source launch (no build needed), JDK 17+:
//   java -XX:StartFlightRecording=... Workload.java <scenario> <durationSeconds>
//
// Each scenario plants ONE known pathology so we have GROUND TRUTH to score
// the LLM's diagnosis against. The method/field names below ARE the answer key.
//
//   cpu   -> CPU hot path:    expensiveHashLoop() dominates execution samples
//   alloc -> memory leak:     handleUpload() allocates; LEAK_CACHE retains forever
//   lock  -> lock contention: criticalSection() contends on SHARED_LOCK
//
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class Workload {

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "cpu";
        long durationMs = (args.length > 1 ? Long.parseLong(args[1]) : 20) * 1000L;
        long deadline = System.nanoTime() + durationMs * 1_000_000L;
        System.out.println("[workload] scenario=" + scenario + " duration=" + (durationMs / 1000) + "s");

        switch (scenario) {
            case "cpu":   runCpu(deadline);   break;
            case "alloc": runAlloc(deadline); break;
            case "lock":  runLock(deadline);  break;
            default: System.err.println("unknown scenario: " + scenario + " (use cpu|alloc|lock)"); System.exit(2);
        }
        System.out.println("[workload] done");
    }

    // ---- GROUND TRUTH (cpu): expensiveHashLoop is the hot path -------------
    private static void runCpu(long deadline) {
        long n = 0;
        while (System.nanoTime() < deadline) { processRequest(n++); }
        System.out.println("[cpu] processed " + n + " requests");
    }
    private static void processRequest(long i) { expensiveHashLoop("request-" + i); }
    private static String expensiveHashLoop(String input) {
        try {
            String s = input;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (int k = 0; k < 2000; k++) {            // pointless re-hashing = the bug
                byte[] d = md.digest(s.getBytes());
                StringBuilder sb = new StringBuilder();
                for (byte b : d) sb.append(Integer.toHexString(b & 0xff));
                s = sb.toString();
            }
            return s;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ---- GROUND TRUTH (alloc): handleUpload leaks into LEAK_CACHE ----------
    private static final List<byte[]> LEAK_CACHE = new ArrayList<>(); // never cleared = the leak
    private static void runAlloc(long deadline) {
        long n = 0;
        while (System.nanoTime() < deadline) { handleUpload(n++); }
        System.out.println("[alloc] handled " + n + " uploads; LEAK_CACHE=" + LEAK_CACHE.size());
    }
    private static void handleUpload(long i) {
        byte[] payload = new byte[64 * 1024];          // 64KB per "upload"
        payload[0] = (byte) i;
        LEAK_CACHE.add(payload);                        // retained forever -> leak
        if ((i & 0x3F) == 0) {                          // small throttle so we don't OOM in 20s
            try { Thread.sleep(1); } catch (InterruptedException ignored) {}
        }
    }

    // ---- GROUND TRUTH (lock): criticalSection contends on SHARED_LOCK ------
    private static final Object SHARED_LOCK = new Object();
    private static void runLock(long deadline) throws InterruptedException {
        int threads = 16;
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                while (System.nanoTime() < deadline) criticalSection();
                done.countDown();
            }, "worker-" + t).start();
        }
        done.await();
        System.out.println("[lock] all workers done");
    }
    private static void criticalSection() {
        synchronized (SHARED_LOCK) {                    // 16 threads, one lock = contention
            long until = System.nanoTime() + 8_000_000L; // hold ~8ms so others block past JFR threshold
            while (System.nanoTime() < until) { /* busy-hold under lock */ }
        }
    }
}
