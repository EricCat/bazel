// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.profiler;

import static com.google.devtools.build.lib.profiler.ProfilerTask.CRITICAL_PATH;
import static com.google.devtools.build.lib.profiler.ProfilerTask.CRITICAL_PATH_COMPONENT;
import static com.google.devtools.build.lib.profiler.ProfilerTask.TASK_COUNT;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.util.VarInt;
import com.google.devtools.build.lib.vfs.Path;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Holds parsed profile file information and provides various ways of
 * accessing it (mostly through different dictionaries or sorted lists).
 *
 * Class should not be instantiated directly but through the use of the
 * ProfileLoader.loadProfile() method.
 */
public class ProfileInfo {

  /**
   * Immutable container for the aggregated stats.
   */
  public static final class AggregateAttr {
    public final int count;
    public final long totalTime;

    AggregateAttr(int count, long totalTime) {
      this.count = count;
      this.totalTime = totalTime;
    }
  }

  /**
   * Immutable compact representation of the Map<ProfilerTask, AggregateAttr>.
   */
  static final class CompactStatistics {
    final byte[] content;

    CompactStatistics(byte[] content) {
      this.content = content;
    }

    /**
     * Create compact task statistic instance using provided array.
     * Array length must exactly match ProfilerTask value space.
     * Each statistic is stored in the array according to the ProfilerTask
     * value ordinal() number. Absent statistics are represented by null.
     */
    CompactStatistics(AggregateAttr[] stats) {
      Preconditions.checkArgument(stats.length == TASK_COUNT);
      ByteBuffer sink = ByteBuffer.allocate(TASK_COUNT * (1 + 5 + 10));
      for (int i = 0; i < TASK_COUNT; i++) {
        if (stats[i] != null && stats[i].count > 0) {
          sink.put((byte) i);
          VarInt.putVarInt(stats[i].count, sink);
          VarInt.putVarLong(stats[i].totalTime, sink);
        }
      }
      content = sink.position() > 0 ? Arrays.copyOf(sink.array(), sink.position()) : null;
    }

    boolean isEmpty() { return content == null; }

    /**
     * Converts instance back into AggregateAttr[TASK_COUNT]. See
     * constructor documentation for more information.
     */
    AggregateAttr[] toArray() {
      AggregateAttr[] stats = new AggregateAttr[TASK_COUNT];
      if (!isEmpty()) {
        ByteBuffer source = ByteBuffer.wrap(content);
        while (source.hasRemaining()) {
          byte id = source.get();
          int count = VarInt.getVarInt(source);
          long time = VarInt.getVarLong(source);
          stats[id] = new AggregateAttr(count, time);
        }
      }
      return stats;
    }

    /**
     * Returns AggregateAttr instance for the given ProfilerTask value.
     */
    AggregateAttr getAttr(ProfilerTask task) {
      if (isEmpty()) { return ZERO; }
      ByteBuffer source = ByteBuffer.wrap(content);
      byte id = (byte) task.ordinal();
      while (source.hasRemaining()) {
        if (id == source.get()) {
          int count = VarInt.getVarInt(source);
          long time = VarInt.getVarLong(source);
          return new AggregateAttr(count, time);
        } else {
          VarInt.getVarInt(source);
          VarInt.getVarLong(source);
        }
      }
      return ZERO;
    }

    /**
     * Returns cumulative time stored in this instance across whole
     * ProfilerTask dimension.
     */
    long getTotalTime() {
      if (isEmpty()) { return 0; }
      ByteBuffer source = ByteBuffer.wrap(content);
      long totalTime = 0;
      while (source.hasRemaining()) {
        source.get();
        VarInt.getVarInt(source);
        totalTime += VarInt.getVarLong(source);
      }
      return totalTime;
    }
  }

  /**
   * Container for the profile record information.
   *
   * <p> TODO(bazel-team): (2010) Current Task instance heap size is 72 bytes. And there are
   * millions of them. Consider trimming some attributes.
   */
  public final class Task implements Comparable<Task> {
    public final long threadId;
    public final int id;
    public final int parentId;
    public final long startTime;
    public final long duration;
    public final ProfilerTask type;
    final CompactStatistics stats;
    // Contains statistic for a task and all subtasks. Populated only for root tasks.
    CompactStatistics aggregatedStats = null;
    // Subtasks are stored as an array for performance and memory utilization
    // reasons (we can easily deal with millions of those objects).
    public Task[] subtasks = NO_TASKS;
    final int descIndex;
    // Reference to the related task (e.g. ACTION_GRAPH->ACTION task relation).
    private Task relatedTask;

    Task(long threadId, int id, int parentId, long startTime, long duration,
         ProfilerTask type, int descIndex, CompactStatistics stats) {
      this.threadId = threadId;
      this.id = id;
      this.parentId = parentId;
      this.startTime = startTime;
      this.duration = duration;
      this.type = type;
      this.descIndex = descIndex;
      this.stats = stats;
      relatedTask = null;
    }

    public String getDescription() {
      return descriptionList.get(descIndex);
    }

    public boolean hasStats() {
      return !stats.isEmpty();
    }

    public long getInheritedDuration() {
      return stats.getTotalTime();
    }

    public AggregateAttr[] getStatAttrArray() {
      Preconditions.checkNotNull(stats);
      return stats.toArray();
    }

    private void combineStats(int[] counts, long[] duration) {
      int ownIndex = type.ordinal();
      if (parentId != 0) {
        // Parent task already accounted for this task total duration. We need to adjust
        // for the inherited duration.
        duration[ownIndex] -= getInheritedDuration();
      }
      AggregateAttr[] ownStats = stats.toArray();
      for (int i = 0; i < TASK_COUNT; i++) {
        AggregateAttr attr = ownStats[i];
        if (attr != null) {
          counts[i] += attr.count;
          duration[i] += attr.totalTime;
        }
      }
      for (Task task : subtasks) {
        task.combineStats(counts, duration);
      }
    }

    /**
     * Calculates aggregated statistics covering all subtasks (including
     * nested ones). Must be called only for parent tasks.
     */
    void calculateRootStats() {
      Preconditions.checkState(parentId == 0);
      int[] counts = new int[TASK_COUNT];
      long[] duration = new long[TASK_COUNT];
      combineStats(counts, duration);
      AggregateAttr[] statArray = ProfileInfo.createEmptyStatArray();
      for (int i = 0; i < TASK_COUNT; i++) {
        statArray[i] = new AggregateAttr(counts[i], duration[i]);
      }
      this.aggregatedStats = new CompactStatistics(statArray);
    }

    @Override
    public boolean equals(Object o) {
      return (o instanceof ProfileInfo.Task) && ((Task) o).id == this.id;
    }

    @Override
    public int hashCode() {
      return this.id;
    }

    @Override
    public String toString() {
      return type + "(" + id + "," + getDescription() + ")";
    }

    /**
     * Tasks records by default sorted by their id. Since id was obtained using
     * AtomicInteger, this comparison will correctly sort tasks in time-ascending
     * order regardless of their origin thread.
     */
    @Override
    public int compareTo(Task task) {
      return this.id - task.id;
    }
  }

  /**
   * Represents node on critical build path
   */
  public static final class CriticalPathEntry {
    public final Task task;
    public final long duration;
    public final long cumulativeDuration;
    public final CriticalPathEntry next;

    private long criticalTime = 0L;

    public CriticalPathEntry(Task task, long duration, CriticalPathEntry next) {
      this.task = task;
      this.duration = duration;
      this.next = next;
      this.cumulativeDuration =
          duration + (next != null ? next.cumulativeDuration : 0);
    }

    private void setCriticalTime(long duration) {
      criticalTime = duration;
    }

    public long getCriticalTime() {
      return criticalTime;
    }
  }

  /**
   * Helper class to create space-efficient task multimap, used to associate
   * array of tasks with specific key.
   */
  private abstract static class TaskMapCreator<K> implements Comparator<Task> {
    @Override
    public abstract int compare(Task a, Task b);
    public abstract K getKey(Task task);

    public Map<K, Task[]> createTaskMap(List<Task> taskList) {
      // Created map usually will end up with thousands of entries, so we
      // preinitialize it to the 10000.
      Map<K, Task[]> taskMap = Maps.newHashMapWithExpectedSize(10000);
      if (taskList.isEmpty()) {
        return taskMap;
      }
      Task[] taskArray = taskList.toArray(new Task[taskList.size()]);
      Arrays.sort(taskArray, this);
      K key = getKey(taskArray[0]);
      int start = 0;
      for (int i = 0; i < taskArray.length; i++) {
        K currentKey = getKey(taskArray[i]);
        if (!key.equals(currentKey)) {
          taskMap.put(key, Arrays.copyOfRange(taskArray, start, i));
          key = currentKey;
          start = i;
        }
      }
      if (start < taskArray.length) {
        taskMap.put(key, Arrays.copyOfRange(taskArray, start, taskArray.length));
      }
      return taskMap;
    }
  }

  /**
   * An interface to pass back profile loading and aggregation messages.
   */
  public interface InfoListener {
    void info(String text);
    void warn(String text);
  }

  private static final Task[] NO_TASKS = new Task[0];
  private static final AggregateAttr ZERO = new AggregateAttr(0, 0);

  public final String comment;
  private boolean corruptedOrIncomplete = false;

  // TODO(bazel-team): (2010) In one case, this list took 277MB of heap. Ideally it should be
  // replaced with a trie.
  private final List<String> descriptionList;
  private final Map<Task, Task> parallelBuilderCompletionQueueTasks;
  public final Map<Long, Task[]> tasksByThread;
  public final List<Task> allTasksById;
  public List<Task> rootTasksById;  // Not final due to the late initialization.
  public final List<Task> phaseTasks;

  public final Map<Task, Task[]> actionDependencyMap;
  // Used to create fake Action tasks if ACTIONG_GRAPH task does not have
  // corresponding ACTION task. For action dependency calculations we will
  // create fake ACTION tasks and assign them negative ids.
  private int fakeActionId = 0;

  private ProfileInfo(String comment) {
    this.comment = comment;

    descriptionList = Lists.newArrayListWithExpectedSize(10000);
    tasksByThread = Maps.newHashMap();
    parallelBuilderCompletionQueueTasks = Maps.newHashMap();
    allTasksById = Lists.newArrayListWithExpectedSize(50000);
    phaseTasks = Lists.newArrayList();
    actionDependencyMap = Maps.newHashMapWithExpectedSize(10000);
  }

  private void addTask(Task task) {
    allTasksById.add(task);
  }

  /**
   * Returns true if profile datafile was corrupted or incomplete
   * and false otherwise.
   */
  public boolean isCorruptedOrIncomplete() {
    return corruptedOrIncomplete;
  }

  /**
   * Returns number of missing actions which were faked in order to complete
   * action graph.
   */
  public int getMissingActionsCount() {
    return -fakeActionId;
  }

  /**
   * Initializes minimum internal data structures necessary to obtain individual
   * task statistic. This method is sufficient to initialize data for dumping.
   */
  public void calculateStats() {
    if (allTasksById.isEmpty()) {
      return;
    }

    Collections.sort(allTasksById);

    Map<Integer, Task[]> subtaskMap = new TaskMapCreator<Integer>() {
      @Override
      public int compare(Task a, Task b) {
        return a.parentId != b.parentId ? a.parentId - b.parentId : a.compareTo(b);
      }
      @Override
      public Integer getKey(Task task) { return task.parentId; }
    }.createTaskMap(allTasksById);
    for (Task task : allTasksById) {
      Task[] subtasks = subtaskMap.get(task.id);
      if (subtasks != null) {
        task.subtasks = subtasks;
      }
    }
    rootTasksById = Arrays.asList(subtaskMap.get(0));

    for (Task task : rootTasksById) {
      task.calculateRootStats();
      if (task.type == ProfilerTask.PHASE) {
        if (!phaseTasks.isEmpty()) {
          phaseTasks.get(phaseTasks.size() - 1).relatedTask = task;
        }
        phaseTasks.add(task);
      }
    }
  }

  /**
   * Analyzes task relationships and dependencies. Used for the detailed profile
   * analysis.
   */
  public void analyzeRelationships() {
    tasksByThread.putAll(new TaskMapCreator<Long>() {
      @Override
      public int compare(Task a, Task b) {
        return a.threadId != b.threadId ? (a.threadId < b.threadId ? -1 : 1) : a.compareTo(b);
      }
      @Override
      public Long getKey(Task task) { return task.threadId; }
    }.createTaskMap(rootTasksById));

    buildDependencyMap();
  }

  /**
   * Calculates cumulative time attributed to the specific task type.
   * Expects to be called only for root (parentId = 0) tasks.
   * calculateStats() must have been called first.
   */
  public AggregateAttr getStatsForType(ProfilerTask type, Collection<Task> tasks) {
    long totalTime = 0;
    int count = 0;
    for (Task task : tasks) {
      if (task.parentId > 0) {
        throw new IllegalArgumentException("task " + task.id + " is not a root task");
      }
      AggregateAttr attr = task.aggregatedStats.getAttr(type);
      count += attr.count;
      totalTime += attr.totalTime;
      if (task.type == type) {
        count++;
        totalTime += (task.duration - task.getInheritedDuration());
      }
    }
    return new AggregateAttr(count, totalTime);
  }

  /**
   * Returns list of all root tasks related to (in other words, started during)
   * the specified phase task.
   */
  public List<Task> getTasksForPhase(Task phaseTask) {
    Preconditions.checkArgument(phaseTask.type == ProfilerTask.PHASE,
      "Unsupported task type %s", phaseTask.type);

    // Algorithm below takes into account fact that rootTasksById list is sorted
    // by the task id and task id values are monotonically increasing with time
    // (this property is guaranteed by the profiler). Thus list is effectively
    // sorted by the startTime. We are trying to select a sublist that includes
    // all tasks that were started later than the given task but earlier than
    // its completion time.
    int startIndex = Collections.binarySearch(rootTasksById, phaseTask);
    Preconditions.checkState(startIndex >= 0,
        "Phase task %s is not a root task", phaseTask.id);
    int endIndex = (phaseTask.relatedTask != null)
        ? Collections.binarySearch(rootTasksById, phaseTask.relatedTask)
        : rootTasksById.size();
    Preconditions.checkState(endIndex >= startIndex,
        "Failed to find end of the phase marked by the task %s", phaseTask.id);
    return rootTasksById.subList(startIndex, endIndex);
  }

  /**
   * Returns task with "Build artifacts" description - corresponding to the
   * execution phase. Usually used to location ACTION_GRAPH task tree.
   */
  public Task getPhaseTask(ProfilePhase phase) {
    for (Task task : phaseTasks) {
      if (task.getDescription().equals(phase.description)) {
        return task;
      }
    }
    return null;
  }

  /**
   * Returns duration of the given phase in ns.
   */
  public long getPhaseDuration(Task phaseTask) {
    Preconditions.checkArgument(phaseTask.type == ProfilerTask.PHASE,
        "Unsupported task type %s", phaseTask.type);

    long duration;
    if (phaseTask.relatedTask != null) {
      duration = phaseTask.relatedTask.startTime - phaseTask.startTime;
    } else {
      Task lastTask = rootTasksById.get(rootTasksById.size() - 1);
      duration = lastTask.startTime + lastTask.duration - phaseTask.startTime;
    }
    Preconditions.checkState(duration >= 0);
    return duration;
  }


  /**
   * Builds map of dependencies between ACTION tasks based on dependencies
   * between ACTION_GRAPH tasks
   */
  private Task buildActionTaskTree(Task actionGraphTask, List<Task> actionTasksByDescription) {
    Task actionTask = actionGraphTask.relatedTask;
    if (actionTask == null) {
      actionTask = actionTasksByDescription.get(actionGraphTask.descIndex);
      if (actionTask == null) {
        // If we cannot find ACTION task that corresponds to the ACTION_GRAPH task,
        // most likely scenario is that we dealing with either aborted or failed
        // build. In this case we will find or create fake zero-duration action
        // task and still reconstruct dependency graph.
        actionTask = new Task(-1, --fakeActionId, 0, 0, 0,
            ProfilerTask.ACTION, actionGraphTask.descIndex, new CompactStatistics((byte[]) null));
        actionTask.calculateRootStats();
        actionTasksByDescription.set(actionGraphTask.descIndex, actionTask);
      }
      actionGraphTask.relatedTask = actionTask;
    }
    if (actionGraphTask.subtasks.length != 0) {
      List<Task> list = Lists.newArrayListWithCapacity(actionGraphTask.subtasks.length);
      for (Task task : actionGraphTask.subtasks) {
        if (task.type == ProfilerTask.ACTION_GRAPH) {
          list.add(buildActionTaskTree(task, actionTasksByDescription));
        }
      }
      if (!list.isEmpty()) {
        Task[] actionPrerequisites = list.toArray(new Task[list.size()]);
        Arrays.sort(actionPrerequisites);
        actionDependencyMap.put(actionTask, actionPrerequisites);
      }
    }
    return actionTask;
  }

  /**
   * Builds map of dependencies between ACTION tasks based on dependencies
   * between ACTION_GRAPH tasks. Root of that dependency tree would be
   * getBuildPhaseTask().
   *
   * <p> Also marks related ACTION and ACTION_SUBMIT tasks.
   */
  private void buildDependencyMap() {
    Task analysisPhaseTask = getPhaseTask(ProfilePhase.ANALYZE);
    Task executionPhaseTask = getPhaseTask(ProfilePhase.EXECUTE);
    if ((executionPhaseTask == null) || (analysisPhaseTask == null)) {
      return;
    }
    // Association between ACTION_GRAPH tasks and ACTION tasks can be established through
    // description id. So we create appropriate xref list.
    List<Task> actionTasksByDescription = Lists.newArrayList(new Task[descriptionList.size()]);
    for (Task task : getTasksForPhase(executionPhaseTask)) {
      if (task.type == ProfilerTask.ACTION) {
        actionTasksByDescription.set(task.descIndex, task);
      }
    }
    List<Task> list = new ArrayList<>();
    for (Task task : getTasksForPhase(analysisPhaseTask)) {
      if (task.type == ProfilerTask.ACTION_GRAPH) {
        list.add(buildActionTaskTree(task, actionTasksByDescription));
      }
    }
    Task[] actionPrerequisites = list.toArray(new Task[list.size()]);
    Arrays.sort(actionPrerequisites);
    actionDependencyMap.put(executionPhaseTask, actionPrerequisites);

    // Scan through all execution phase tasks to identify ACTION_SUBMIT tasks and associate
    // them with ACTION task counterparts. ACTION_SUBMIT tasks are not necessarily root
    // tasks so we need to scan ALL tasks.
    for (Task task : allTasksById.subList(executionPhaseTask.id, allTasksById.size())) {
      if (task.type == ProfilerTask.ACTION_SUBMIT) {
        Task actionTask = actionTasksByDescription.get(task.descIndex);
        if (actionTask != null) {
          task.relatedTask = actionTask;
          actionTask.relatedTask = task;
        }
      } else if (task.type == ProfilerTask.ACTION_BUILDER) {
        Task actionTask = actionTasksByDescription.get(task.descIndex);
        if (actionTask != null) {
          parallelBuilderCompletionQueueTasks.put(actionTask, task);
        }
      }
    }
  }

  /**
   * Calculates critical path for the specific action
   * excluding specified nested task types (e.g. VFS-related time) and not
   * accounting for overhead related to the Blaze scheduler.
   */
  private CriticalPathEntry computeCriticalPathForAction(
      Set<ProfilerTask> ignoredTypes, Set<Task> ignoredTasks,
      Task actionTask, Map<Task, CriticalPathEntry> cache, Deque<Task> stack) {

    // Loop check is expensive for the Deque (and we don't want to use hash sets because adding
    // and removing elements was shown to be very expensive). To avoid quadratic costs we're
    // checking for infinite loop only when deque's size equal to the power of 2 and >= 32.
    if ((stack.size() & 0x1F) == 0 && Integer.bitCount(stack.size()) == 1) {
      if (stack.contains(actionTask)) {
        // This situation will appear if build has ended with the
        // IllegalStateException thrown by the
        // ParallelBuilder.getNextCompletedAction(), warning user about
        // possible cycle in the dependency graph. But the exception text
        // is more friendly and will actually identify the loop.
        // Do not use Preconditions class below due to the very expensive
        // toString() calls used in the message.
        throw new IllegalStateException ("Dependency graph contains loop:\n"
            + actionTask + " in the\n" + Joiner.on('\n').join(stack));
      }
    }
    stack.addLast(actionTask);
    CriticalPathEntry entry;
    try {
      entry = cache.get(actionTask);
      long entryDuration = 0;
      if (entry == null) {
        Task[] actionPrerequisites = actionDependencyMap.get(actionTask);
        if (actionPrerequisites != null) {
          for (Task task : actionPrerequisites) {
            CriticalPathEntry candidate =
              computeCriticalPathForAction(ignoredTypes, ignoredTasks, task, cache, stack);
            if (entry == null || entryDuration < candidate.cumulativeDuration) {
              entry = candidate;
              entryDuration = candidate.cumulativeDuration;
            }
          }
        }
        if (actionTask.type == ProfilerTask.ACTION) {
          long duration = actionTask.duration;
          if (ignoredTasks.contains(actionTask)) {
            duration = 0L;
          } else {
            for (ProfilerTask type : ignoredTypes) {
              duration -= actionTask.aggregatedStats.getAttr(type).totalTime;
            }
          }

          entry = new CriticalPathEntry(actionTask, duration, entry);
          cache.put(actionTask, entry);
        }
      }
    } finally {
      stack.removeLast();
    }
    return entry;
  }

  /**
   * Returns the critical path information from the {@code CriticalPathComputer} recorded stats.
   * This code does not have the "Critical" column (Time difference if we removed this node from
   * the critical path).
   */
  public CriticalPathEntry getCriticalPathNewVersion() {
    for (Task task : rootTasksById) {
      if (task.type == CRITICAL_PATH) {
        CriticalPathEntry entry = null;
        for (Task shared : task.subtasks) {
          entry = new CriticalPathEntry(shared, shared.duration, entry);
        }
        return entry;
      }
    }
    return null;
  }

  /**
   * Calculates critical path for the given action graph excluding
   * specified tasks (usually ones that belong to the "real" critical path).
   */
  public CriticalPathEntry getCriticalPath(Set<ProfilerTask> ignoredTypes) {
    Task actionTask = getPhaseTask(ProfilePhase.EXECUTE);
    if (actionTask == null) {
      return null;
    }
    Map <Task, CriticalPathEntry> cache = Maps.newHashMapWithExpectedSize(1000);
    CriticalPathEntry result = computeCriticalPathForAction(ignoredTypes,
        new HashSet<Task>(), actionTask, cache,
        new ArrayDeque<Task>());
    if (result != null) {
      return result;
    }
    return getCriticalPathNewVersion();
  }

  /**
   * Calculates critical path time that will be saved by eliminating specific
   * entry from the critical path
   */
  public void analyzeCriticalPath(Set<ProfilerTask> ignoredTypes, CriticalPathEntry path) {
    // With light critical path we do not need to analyze since it is already preprocessed
    // by blaze build.
    if (path != null && path.task.type == CRITICAL_PATH_COMPONENT) {
      return;
    }
    for (CriticalPathEntry entry = path; entry != null; entry = entry.next) {
      Map <Task, CriticalPathEntry> cache = Maps.newHashMapWithExpectedSize(1000);
      entry.setCriticalTime(path.cumulativeDuration -
          computeCriticalPathForAction(ignoredTypes, Sets.newHashSet(entry.task),
          getPhaseTask(ProfilePhase.EXECUTE), cache,  new ArrayDeque<Task>())
          .cumulativeDuration);
    }
  }

  /**
   * Return the next critical path entry for the task or null if there is none.
   */
  public CriticalPathEntry getNextCriticalPathEntryForTask(CriticalPathEntry path, Task task) {
    for (CriticalPathEntry entry = path; entry != null; entry = entry.next) {
      if (entry.task.id == task.id) {
        return entry;
      }
    }
    return null;
  }

  /**
   * Returns time action waited in the execution queue (difference between
   * ACTION task start time and ACTION_SUBMIT task start time).
   */
  public long getActionWaitTime(Task actionTask) {
    // Light critical path does not record wait time.
    if (actionTask.type == ProfilerTask.CRITICAL_PATH_COMPONENT) {
      return 0;
    }
    Preconditions.checkArgument(actionTask.type == ProfilerTask.ACTION);
    if (actionTask.relatedTask != null) {
      Preconditions.checkState(actionTask.relatedTask.type == ProfilerTask.ACTION_SUBMIT);
      long time = actionTask.startTime - actionTask.relatedTask.startTime;
      Preconditions.checkState(time >= 0);
      return time;
    } else {
      return 0L; // submission time is not available.
    }
  }

  /**
   * Returns time action waited in the parallel builder completion queue
   * (difference between ACTION task end time and ACTION_BUILDER start time).
   */
  public long getActionQueueTime(Task actionTask) {
    // Light critical path does not record queue time.
    if (actionTask.type == ProfilerTask.CRITICAL_PATH_COMPONENT) {
      return 0;
    }
    Preconditions.checkArgument(actionTask.type == ProfilerTask.ACTION);
    Task related = parallelBuilderCompletionQueueTasks.get(actionTask);
    if (related != null) {
      Preconditions.checkState(related.type == ProfilerTask.ACTION_BUILDER);
      long time = related.startTime - (actionTask.startTime + actionTask.duration);
      Preconditions.checkState(time >= 0);
      return time;
    } else {
      return 0L; // queue task is not available.
    }
  }

  /**
   * Returns an empty array used to store task statistics. Array index
   * corresponds to the ProfilerTask ordinal() value associated with the
   * given statistic. Absent statistics are stored as null.
   * <p>
   * In essence, it is a fast equivalent of Map<ProfilerTask, AggregateAttr>.
   */
  public static AggregateAttr[] createEmptyStatArray() {
    return new AggregateAttr[TASK_COUNT];
  }

  /**
   * Loads and parses Blaze profile file.
   *
   * @param profileFile profile file path
   *
   * @return ProfileInfo object with some fields populated (call calculateStats()
   *         and analyzeRelationships() to populate the remaining fields)
   * @throws UnsupportedEncodingException if the file format is invalid
   * @throws IOException if the file can't be read
   */
  public static ProfileInfo loadProfile(Path profileFile)
      throws IOException {
    // It is extremely important to wrap InflaterInputStream using
    // BufferedInputStream because majority of reads would be done using
    // readInt()/readLong() methods and InflaterInputStream is very inefficient
    // in handling small read requests (performance difference with 1MB buffer
    // used below is almost 10x).
    DataInputStream in = new DataInputStream(
        new BufferedInputStream(new InflaterInputStream(
        profileFile.getInputStream(), new Inflater(false), 65536), 1024 * 1024));

    if (in.readInt() != Profiler.MAGIC) {
      in.close();
      throw new UnsupportedEncodingException("Invalid profile datafile format");
    }
    if (in.readInt() != Profiler.VERSION) {
      in.close();
      throw new UnsupportedEncodingException("Incompatible profile datafile version");
    }
    String fileComment = in.readUTF();

    // Read list of used record types
    int typeCount = in.readInt();
    boolean hasUnknownTypes = false;
    Set<String> supportedTasks = new HashSet<>();
    for (ProfilerTask task : ProfilerTask.values()) {
      supportedTasks.add(task.toString());
    }
    List<ProfilerTask> typeList = new ArrayList<>();
    for (int i = 0; i < typeCount; i++) {
      String name = in.readUTF();
      if (supportedTasks.contains(name)) {
        typeList.add(ProfilerTask.valueOf(name));
      } else {
        hasUnknownTypes = true;
        typeList.add(ProfilerTask.UNKNOWN);
      }
    }

    ProfileInfo info = new ProfileInfo(fileComment);

    // Read record until we encounter end marker (-1).
    // TODO(bazel-team): Maybe this still should handle corrupted(truncated) files.
    try {
      int size;
      while ((size = in.readInt()) != Profiler.EOF_MARKER) {
        byte[] backingArray = new byte[size];
        in.readFully(backingArray);
        ByteBuffer buffer = ByteBuffer.wrap(backingArray);
        long threadId = VarInt.getVarLong(buffer);
        int id = VarInt.getVarInt(buffer);
        int parentId = VarInt.getVarInt(buffer);
        long startTime = VarInt.getVarLong(buffer);
        long duration = VarInt.getVarLong(buffer);
        int descIndex = VarInt.getVarInt(buffer) - 1;
        if (descIndex == -1) {
          String desc = in.readUTF();
          descIndex = info.descriptionList.size();
          info.descriptionList.add(desc);
        }
        ProfilerTask type = typeList.get(buffer.get());
        byte[] stats = null;
        if (buffer.hasRemaining()) {
          // Copy aggregated stats.
          int offset = buffer.position();
          stats = Arrays.copyOfRange(backingArray, offset, size);
          if (hasUnknownTypes) {
            while (buffer.hasRemaining()) {
              byte attrType = buffer.get();
              if (typeList.get(attrType) == ProfilerTask.UNKNOWN) {
                // We're dealing with unknown aggregated type - update stats array to
                // use ProfilerTask.UNKNOWN.ordinal() value.
                stats[buffer.position() - 1 - offset] = (byte) ProfilerTask.UNKNOWN.ordinal();
              }
              VarInt.getVarInt(buffer);
              VarInt.getVarLong(buffer);
            }
          }
        }
        ProfileInfo.Task task =  info.new Task(threadId, id, parentId, startTime, duration, type,
            descIndex, new CompactStatistics(stats));
        info.addTask(task);
      }
    } catch (IOException e) {
      info.corruptedOrIncomplete = true;
    } finally {
      in.close();
    }

    return info;
  }

  /**
   * Loads and parses Blaze profile file, and reports what it is doing.
   *
   * @param profileFile profile file path
   * @param reporter for progress messages and warnings
   *
   * @return ProfileInfo object with most fields populated
   *         (call analyzeRelationships() to populate the remaining fields)
   * @throws UnsupportedEncodingException if the file format is invalid
   * @throws IOException if the file can't be read
   */
  public static ProfileInfo loadProfileVerbosely(Path profileFile, InfoListener reporter)
      throws IOException {
    reporter.info("Loading " + profileFile.getPathString());
    ProfileInfo profileInfo = ProfileInfo.loadProfile(profileFile);
    if (profileInfo.isCorruptedOrIncomplete()) {
      reporter.warn("Profile file is incomplete or corrupted - not all records were parsed");
    }
    reporter.info(profileInfo.comment + ", " + profileInfo.allTasksById.size() + " record(s)");
    return profileInfo;
  }

  /*
   * Sorts and aggregates Blaze profile file, and reports what it is doing.
   */
  public static void aggregateProfile(ProfileInfo profileInfo, InfoListener reporter) {
    reporter.info("Aggregating task statistics");
    profileInfo.calculateStats();
  }

}
