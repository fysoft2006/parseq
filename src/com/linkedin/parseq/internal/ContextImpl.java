/*
 * Copyright 2012 LinkedIn, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.linkedin.parseq.internal;

import com.linkedin.parseq.After;
import com.linkedin.parseq.BaseTask;
import com.linkedin.parseq.Cancellable;
import com.linkedin.parseq.Context;
import com.linkedin.parseq.EarlyFinishException;
import com.linkedin.parseq.Task;
import com.linkedin.parseq.promise.Promise;
import com.linkedin.parseq.promise.PromiseListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * @author Chris Pettitt (cpettitt@linkedin.com)
 * @author Chi Chan (ckchan@linkedin.com)
 */
public class ContextImpl implements Context, Cancellable
{
  private static final Task<?> NO_PARENT = null;
  private static final List<Task<?>> NO_PREDECESSORS = Collections.emptyList();

  private static final Exception EARLY_FINISH_EXCEPTION;

  static
  {
    EARLY_FINISH_EXCEPTION = new EarlyFinishException("Task cancelled because parent was already finished");

    // Clear out everything but the last frame
    final StackTraceElement[] stackTrace = EARLY_FINISH_EXCEPTION.getStackTrace();
    if (stackTrace.length > 0) {
      EARLY_FINISH_EXCEPTION.setStackTrace(Arrays.copyOf(EARLY_FINISH_EXCEPTION.getStackTrace(), 1));
    }
  }

  /**
   * Plan level configuration and facilities.
   */
  private final PlanContext _planContext;

  private final Task<Object> _task;

  // A thread local that holds the root task iff the root task is being executed
  // on the current thread. In all other cases, this thread local will hold a
  // null value.
  private final ThreadLocal<Task<?>> _inTask = new ThreadLocal<Task<?>>();

  private final Task<?> _parent;
  private final List<Task<?>> _predecessorTasks;

  private final ConcurrentLinkedQueue<Cancellable> _cancellables = new ConcurrentLinkedQueue<Cancellable>();

  public ContextImpl(final PlanContext planContext,
                     final Task<?> task)
  {
    this(planContext, task, NO_PARENT, NO_PREDECESSORS);
  }

  private ContextImpl(final PlanContext planContext,
                      final Task<?> task,
                      final Task<?> parent,
                      final List<Task<?>> predecessorTasks)
  {
    _planContext = planContext;
    _task = InternalUtil.unwildcardTask(task);
    _parent = parent;
    _predecessorTasks = predecessorTasks;
  }

  public void runTask()
  {
    // Cancel everything created by this task once it finishes
    _task.addListener(new PromiseListener<Object>()
    {
      @Override
      public void onResolved(Promise<Object> resolvedPromise)
      {
        for (Iterator<Cancellable> it = _cancellables.iterator(); it.hasNext(); )
        {
          final Cancellable cancellable = it.next();
          cancellable.cancel(EARLY_FINISH_EXCEPTION);
          it.remove();
        }
      }
    });

    _planContext.execute(new PrioritizableRunnable()
    {
      @Override
      public void run()
      {
        _inTask.set(_task);
        try
        {
          _task.contextRun(ContextImpl.this, _planContext.getTaskLogger(), _parent, _predecessorTasks);
        }
        finally
        {
          _inTask.remove();
        }
      }

      @Override
      public int getPriority()
      {
        return _task.getPriority();
      }
    });
  }

  @Override
  public Cancellable createTimer(final long time, final TimeUnit unit,
                                 final Task<?> task)
  {
    checkInTask();
    final Cancellable cancellable = _planContext.schedule(time, unit, new Runnable()
    {
      @Override
      public void run()
      {
        runSubTask(task, NO_PREDECESSORS);
      }
    });
    _cancellables.add(cancellable);
    return cancellable;
  }

  @Override
  public void run(final Task<?>... tasks)
  {
    checkInTask();
    for (final Task<?> task : tasks)
    {
      runSubTask(task, NO_PREDECESSORS);
    }
  }

  @Override
  public After after(final Promise<?>... promises)
  {
    checkInTask();

    final List<Task<?>> tmpPredecessorTasks = new ArrayList<Task<?>>();
    for (Promise<?> promise : promises)
    {
      if (promise instanceof Task)
      {
        tmpPredecessorTasks.add((Task<?>) promise);
      }
    }
    final List<Task<?>> predecessorTasks = Collections.unmodifiableList(tmpPredecessorTasks);

    return new After() {
      @Override
      public void run(final Task<?> task)
      {
        InternalUtil.after(new PromiseListener()
        {
          @Override
          public void onResolved(Promise resolvedPromise)
          {
            runSubTask(task, predecessorTasks);
          }
        }, promises);
      }

      @Override
      public Task<?> runSideEffect(final Task<?> task)
      {
        final Task<?> taskWrapper = createSideEffectWrapper(task, predecessorTasks);
        InternalUtil.after(new PromiseListener()
        {
          @Override
          public void onResolved(Promise resolvedPromise)
          {
            runSideEffectSubTask(taskWrapper, predecessorTasks);
          }
        }, promises);
        return taskWrapper;
      }
    };
  }

  @Override
  public boolean cancel(Exception reason)
  {
    boolean result = _task.cancel(reason);
    //run the task to capture the trace data
    _task.contextRun(this, _planContext.getTaskLogger(), _parent, _predecessorTasks);
    return result;
  }

  @Override
  public Object getEngineProperty(String key)
  {
    return _planContext.getEngineProperty(key);
  }

  private ContextImpl createSubContext(final Task<?> task, final List<Task<?>> predecessors)
  {
    return new ContextImpl(_planContext, task, _task, predecessors);
  }

  private void runSubTask(final Task<?> task, final List<Task<?>> predecessors)
  {
    final ContextImpl subContext = createSubContext(task, predecessors);
    if (!isDone())
    {
      _cancellables.add(subContext);
      subContext.runTask();
    }
    else
    {
      subContext.cancel(EARLY_FINISH_EXCEPTION);
    }
  }

  private Task<?> createSideEffectWrapper(final Task<?> task, final List<Task<?>> predecessors)
  {
    Task<?> taskWrapper = new BaseTask<Object>("sideEffectWrapper")
    {
      @Override
      protected Promise<?> run(Context context) throws Throwable
      {
        for (Task<?> predecessor : predecessors)
          if (predecessor.isFailed())
          {
            return null;
          }
        context.run(task);
        return task;
      }
    };
    return taskWrapper;
  }

  private void runSideEffectSubTask(final Task<?> taskWrapper, final List<Task<?>> predecessors)
  {

    final ContextImpl subContext = createSubContext(taskWrapper, predecessors);
    subContext.runTask();
  }

  private boolean isDone()
  {
    return _task.isDone();
  }

  private void checkInTask()
  {
    if (_inTask.get() != _task)
    {
      throw new IllegalStateException("Context method invoked while not in context's task");
    }
  }
}
