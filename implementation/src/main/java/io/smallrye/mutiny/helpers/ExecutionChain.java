package io.smallrye.mutiny.helpers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ExecutionChain {

    private static final Object NULL = new Object();

    private static final Logger log = Logger.getLogger(ExecutionChain.class.getName());

    private final Deque<ChainTask> tasks = new ArrayDeque<>();
    private final Deque<Object> ctx = new ArrayDeque<>();
    private boolean running = false;
    private boolean done = false;
    private final Thread owner;
    private CurrentExecutionContext currentExecutionContext;

    public ExecutionChain() {
        this.owner = Thread.currentThread();
    }

    public <T> void execute(ChainTask<T> task, T contextual) {
        //if we are adding from outside the scope of the chain we execute directly
        if (owner != Thread.currentThread() || !running) {
            task.runChainTask(contextual, null);
        } else {
            tasks.add(task);
            ctx.add(contextual == null ? NULL : contextual);
        }
    }

    public <T> void executeAndRun(ChainTask<T> task, T contextual) {
        //if we are adding from outside the scope of the chain we execute directly
        if (owner != Thread.currentThread()) {
            task.runChainTask(contextual, null);
        } else {
            tasks.add(task);
            ctx.add(contextual == null ? NULL : contextual);
            if (!running) {
                run();
            }
        }
    }

    public void execute(ChainTask<Void> task) {
        execute(task, null);
    }

    public void executeAndRun(ChainTask<Void> task) {
        executeAndRun(task, null);
    }

    public CurrentExecutionContext currentExecutionContext() {
        if (owner != Thread.currentThread()) {
            return null;
        }
        return currentExecutionContext;
    }

    public void run() {
        try {
            running = true;
            currentExecutionContext = new CurrentExecutionContext();
            ChainTask task = tasks.poll();
            Object c = ctx.poll();
            while (task != null) {
                try {
                    task.runChainTask(c == NULL ? null : c, this);
                } catch (Throwable t) {
                    log.log(Level.SEVERE, "Failed to run task", t);
                }
                task = tasks.poll();
                c = ctx.poll();
            }
        } finally {
            currentExecutionContext.close();
            currentExecutionContext = null;
            running = false;
            done = true;
        }
    }

    public interface ChainTask<T> {

        void runChainTask(T contextual, ExecutionChain chain);
    }

    public class CurrentExecutionContext {

        private final List<Runnable> closeTasks = new ArrayList<>();
        private final Map<String, Object> data = new HashMap<>();

        public void closeTask(Runnable r) {
            closeTasks.add(r);
        }

        public void put(String key, Object value) {
            data.put(key, value);
        }

        public Object get(String key) {
            return data.get(key);
        }

        void close() {
            for (Runnable i : closeTasks) {
                try {
                    i.run();
                } catch (Throwable t) {
                    log.log(Level.SEVERE, "Close task failed", t);
                }
            }
        }
    }

}
