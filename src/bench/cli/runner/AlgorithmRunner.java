package bench.cli.runner;

import engine.core.Optimizer;
import engine.core.OptimizerState;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Moteur d'exécution d'un Optimizer dans un thread séparé.
 * Migré depuis bench.gui pour usage CLI.
 */
public class AlgorithmRunner
{
    public interface StateListener
    {
        void onStateUpdate (String name, OptimizerState state, long elapsedMs);
        void onFinished   (String name, OptimizerState finalState, long elapsedMs);
    }

    private final String name;
    private final Optimizer optimizer;
    private final long timeLimitMs;
    private final List<StateListener> listeners = new CopyOnWriteArrayList<> ();

    private volatile boolean running;
    private Thread thread;
    private long startTime;

    // Throttle : notifier au maximum toutes les 50ms
    private static final long NOTIFY_INTERVAL_MS = 50;

    public AlgorithmRunner (String name, Optimizer optimizer, long timeLimitMs)
    {
        this.name = name;
        this.optimizer = optimizer;
        this.timeLimitMs = timeLimitMs;
    }

    public void addListener (StateListener listener) { listeners.add (listener); }

    public void start ()
    {
        if (running) return;
        running = true;
        thread = new Thread (() -> run (), "Runner-" + name);
        thread.setDaemon (true);
        thread.start ();
    }

    public void stop ()
    {
        running = false;
        if (thread != null) thread.interrupt ();
    }

    public boolean isRunning () { return running; }
    public String getName ()    { return name; }

    private void run ()
    {
        try {
            optimizer.init ();
            startTime = System.currentTimeMillis ();
            long lastNotify = startTime;

            while (running)
            {
                long now = System.currentTimeMillis ();
                if (now - startTime >= timeLimitMs)
                {
                    running = false;
                    break;
                }

                optimizer.step ();

                now = System.currentTimeMillis ();
                if (now - lastNotify >= NOTIFY_INTERVAL_MS)
                {
                    OptimizerState state = optimizer.getState ();
                    long elapsed = now - startTime;
                    for (StateListener l : listeners)
                        l.onStateUpdate (name, state, elapsed);
                    lastNotify = now;
                }
            }

            OptimizerState finalState = optimizer.getState ();
            long elapsed = System.currentTimeMillis () - startTime;
            for (StateListener l : listeners)
                l.onFinished (name, finalState, elapsed);
        } catch (Exception e) {
            e.printStackTrace();
            running = false;
            // Notify listeners about premature termination
            long elapsed = (startTime > 0) ? System.currentTimeMillis() - startTime : 0;
            OptimizerState state = optimizer.getState();
            for (StateListener l : listeners)
                l.onFinished(name, state != null ? state : new OptimizerState(0, 0, 0, Double.NaN, Double.NaN, Double.NaN, null), elapsed);
        }
    }

    public long getElapsedMs ()
    {
        return (startTime > 0) ? System.currentTimeMillis () - startTime : 0;
    }
}
