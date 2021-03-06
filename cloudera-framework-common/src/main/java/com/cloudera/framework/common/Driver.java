package com.cloudera.framework.common;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.mapreduce.Counters;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.util.RunJar;
import org.apache.hadoop.util.ShutdownHookManager;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base driver class providing a standard life-cycle, counter management and
 * logging
 */
public abstract class Driver extends Configured implements Tool {

  public static final int SUCCESS = 0;
  public static final int FAILURE_ARGUMENTS = 10;
  public static final int FAILURE_RUNTIME = 20;

  public static final String CONF_SETTINGS = "driver-site.xml";
  public static final String CONF_APPLICATION = "/application.properties";

  private static final Logger LOG = LoggerFactory.getLogger(Driver.class);

  private static final int FORMAT_TIME_FACTOR = 10;

  private final Map<String, Map<Enum<?>, Long>> counters = new LinkedHashMap<>();

  private List<Object> results = null;

  private Engine engine;

  private static final Properties APP_PROPERTIES = Optional.of(new Properties()).map(properties -> {
    try {
      properties.load(Driver.class.getResourceAsStream(Driver.CONF_APPLICATION));
    } catch (Exception exception) {
      if (LOG.isErrorEnabled()) {
        LOG.error("Failed to load application properties from [" + Driver.CONF_APPLICATION + "]", exception);
      }
    }
    return properties;
  }).orElseGet(Properties::new);

  public Driver(Configuration conf, Engine engine) {
    super(conf);
    this.engine = engine;
  }

  private static String formatTime(long time) {
    StringBuilder string = new StringBuilder(128);
    int factor;
    String unit;
    if (time < 0) {
      time = 0;
      factor = 1;
      unit = "ms";
    } else if (time < FORMAT_TIME_FACTOR * 1000) {
      factor = 1;
      unit = "ms";
    } else if (time < FORMAT_TIME_FACTOR * 1000 * 60) {
      factor = 1000;
      unit = "sec";
    } else if (time < FORMAT_TIME_FACTOR * 1000 * 60 * 60) {
      factor = 1000 * 60;
      unit = "min";
    } else {
      factor = 1000 * 60 * 60;
      unit = "hour";
    }
    string.append("[");
    string.append(time / factor);
    string.append("] ");
    string.append(unit);
    return string.toString();
  }

  /**
   * Provide a brief description of the driver for printing as part of the usage
   */
  public String description() {
    return "";
  }

  /**
   * Declare non-mandatory options, passed in as command line "-Dname=value"
   * switches or typed configurations as part of the {@link Configuration}
   * available from {@link #getConf()}
   *
   * @return the options as a <code>name=value1|value2</code> array
   */
  public String[] options() {
    return new String[0];
  }

  /**
   * Declare mandatory parameters, passed in as command line arguments
   *
   * @return the parameters as a <code>description</code> array
   */
  public String[] parameters() {
    return new String[0];
  }

  /**
   * Prepare the driver
   *
   * @param arguments the arguments passed in via the command line, option switches will
   *                  be populated into the {@link Configuration} available from
   *                  {@link #getConf()}
   * @return {@link #SUCCESS} on success, non {@link #SUCCESS} on
   * failure
   */
  @SuppressWarnings("SameReturnValue")
  public int prepare(String... arguments) throws Exception {
    return SUCCESS;
  }

  /**
   * Execute the driver
   *
   * @return {@link #SUCCESS} on success, non {@link #SUCCESS} on
   * failure
   */
  public abstract int execute() throws Exception;

  /**
   * Clean the driver on shutdown
   *
   * @return {@link #SUCCESS} on success, non {@link #SUCCESS} on
   * failure
   */
  @SuppressWarnings("SameReturnValue")
  public int cleanup() {
    return SUCCESS;
  }

  /**
   * Reset the driver to be used again, should be called if overridden
   */
  public void reset() {
    results = null;
    counters.clear();
  }

  /**
   * Run the driver
   */
  @Override
  final public int run(String[] args) {
    if (LOG.isInfoEnabled()) {
      LOG.info("Driver [" + this.getClass().getSimpleName() + "] started");
    }
    long timeTotal = System.currentTimeMillis();
    ShutdownHookManager.get().addShutdownHook(() -> {
      try {
        cleanup();
      } catch (Exception exception) {
        if (LOG.isErrorEnabled()) {
          LOG.error("Exception raised executing shutdown handler", exception);
        }
      }
    }, RunJar.SHUTDOWN_HOOK_PRIORITY + 1);
    if (Driver.class.getResource("/" + CONF_SETTINGS) != null) {
      getConf().addResource(CONF_SETTINGS);
    }
    reset();
    if (LOG.isTraceEnabled() && getConf() != null) {
      LOG.trace("Driver [" + this.getClass().getCanonicalName() + "] initialised with configuration properties:");
      for (Entry<String, String> entry : getConf()) {
        LOG.trace("\t" + entry.getKey() + "=" + entry.getValue());
      }
    }
    int exitValue = FAILURE_RUNTIME;
    try {
      if ((exitValue = prepare(args)) == SUCCESS) {
        exitValue = execute();
      }
    } catch (Exception exception) {
      if (LOG.isErrorEnabled()) {
        LOG.error("Exception raised executing pipeline handlers", exception);
      }
    } finally {
      try {
        cleanup();
      } catch (Exception exception) {
        if (LOG.isErrorEnabled()) {
          LOG.error("Exception raised cleaning up pipeline handlers", exception);
        }
      }
    }
    timeTotal = System.currentTimeMillis() - timeTotal;
    if (LOG.isInfoEnabled()) {
      if (getCountersGroups().size() > 0) {
        LOG.info("Driver [" + this.getClass().getSimpleName() + "] counters:");
        for (String group : getCountersGroups()) {
          Map<Enum<?>, Long> counters = getCounters(group);
          for (Enum<?> counter : counters.keySet()) {
            LOG.info("\t" + group + "." + counter.toString() + "=" + counters.get(counter));
          }
        }
      }
      if (results != null) {
        LOG.info("Driver [" + this.getClass().getSimpleName() + "] results:");
        results.forEach(result -> LOG.info("\t" + result));
      }
      String exitStatus;
      switch (exitValue) {
        case SUCCESS:
          exitStatus = "SUCCESS";
          break;
        case FAILURE_RUNTIME:
          exitStatus = "RUNTIME FAILURE";
          break;
        case FAILURE_ARGUMENTS:
          exitStatus = "INVALID ARGUMENTS";
          break;
        default:
          exitStatus = "UNKNOWN";
      }
      LOG.info(
        "Driver [" + this.getClass().getSimpleName() + "] finished with status [" + exitStatus +
          "] and exit code [" + exitValue + "] in " + formatTime(timeTotal));
    }
    return exitValue;
  }

  final public int runner(String... arguments) {
    int returnValue;
    try {
      reset();
      returnValue = ToolRunner.run(this, arguments);
    } catch (Exception exception) {
      if (LOG.isErrorEnabled()) {
        LOG.error("Fatal error encountered", exception);
      }
      returnValue = FAILURE_RUNTIME;
    }
    if (returnValue != SUCCESS) {
      if (LOG.isInfoEnabled()) {
        StringBuilder optionsAndParameters = new StringBuilder(256);
        for (int i = 0; i < options().length; i++) {
          switch (engine) {
            case HADOOP:
              optionsAndParameters.append(" [-D").append(options()[i]).append("]");
              break;
            case SPARK:
              optionsAndParameters.append(" [--conf 'spark.driver.extraJavaOptions=-D").append(options()[i]).append("']");
              optionsAndParameters.append(" [--conf 'spark.executor.extraJavaOptions=-D").append(options()[i]).append("']");
              break;
          }
        }
        for (int i = 0; i < parameters().length; i++) {
          optionsAndParameters.append(parameters().length == 0 ? "" : " " + "<" + parameters()[i] + ">");
        }
        if (description() != null && !description().equals("")) {
          LOG.info("Description: " + description());
        }
        LOG.info("Usage: " + (engine.equals(Engine.HADOOP) ? "hadoop" : "spark-submit") + " " +
          this.getClass().getCanonicalName() + " [generic options]" + optionsAndParameters)
        ;
      }
    }
    return returnValue;
  }

  public List<Object> getResults() {
    return results == null ? Collections.emptyList() : results;
  }

  public void addResults(List<Object> results) {
    results.forEach(this::addResult);
  }

  public void addResult(Object result) {
    if (results == null) {
      results = new ArrayList<>();
    }
    results.add(result);
  }

  public Map<String, Map<Enum<?>, Long>> getCounters() {
    return new LinkedHashMap<>(counters);
  }

  public Map<Enum<?>, Long> getCounters(String group) {
    return counters.get(group) == null ? Collections.emptyMap() : new LinkedHashMap<>(counters.get(group));
  }

  public Set<String> getCountersGroups() {
    return new LinkedHashSet<>(counters.keySet());
  }

  public Long getCounter(String group, Enum<?> counter) {
    return counters.get(group) == null || counters.get(group).get(counter) == null ? null : counters.get(group).get(counter);
  }

  protected void addCountersAll(Map<String, Map<Enum<?>, Long>> counters) {
    for (String group : counters.keySet()) {
      addCounters(group, counters.get(group));
    }
  }

  protected void addCounters(Map<Enum<?>, Long> counters) {
    addCounters(this.getClass().getName(), counters);
  }

  protected void addCounters(String group, Map<Enum<?>, Long> counters) {
    this.counters.computeIfAbsent(group, k -> new LinkedHashMap<>());
    for (Enum<?> value : counters.keySet()) {
      if (counters.get(value) != null) {
        this.counters.get(group).put(value,
          (this.counters.get(group).get(value) == null ? 0 : this.counters.get(group).get(value)) + counters.get(value));
      }
    }
  }

  protected void addCounters(Job job, Enum<?>[] values) throws IOException {
    addCounters(this.getClass().getName(), job, values);
  }

  protected void addCounters(String group, Job job, Enum<?>[] values) throws IOException {
    this.counters.computeIfAbsent(group, k -> new LinkedHashMap<>());
    Counters counters = job.getCounters();
    for (Enum<?> value : values) {
      if (counters.findCounter(value) != null) {
        this.counters.get(group).put(value, (this.counters.get(group).get(value) == null ? 0 : this.counters.get(group).get(value))
          + counters.findCounter(value).getValue());
      }
    }
  }

  public Long incrementCounter(Enum<?> counter, long increment) {
    return incrementCounter(this.getClass().getName(), counter, increment);
  }

  public Long incrementCounter(String group, Enum<?> counter, long increment) {
    this.counters.computeIfAbsent(group, k -> new LinkedHashMap<>());
    return counters.get(group).put(counter, (counters.get(group).get(counter) == null ? 0 : counters.get(group).get(counter)) + increment);
  }

  public Long incrementCounter(Enum<?> counter, int increment, String tag, Set<String> set) {
    return incrementCounter(this.getClass().getName(), counter, increment, tag, set);
  }

  public Long incrementCounter(String group, Enum<?> counter, int increment, String tag, Set<String> set) {
    if (set.add(tag)) {
      return incrementCounter(group, counter, increment);
    }
    return counters.get(group).get(counter);
  }

  public static String getApplicationProperty(String key) {
    return (String) APP_PROPERTIES.get(key);
  }

  public enum Engine {
    HADOOP, SPARK
  }

  public enum Counter {
    FILES_IN,
    FILES_OUT,
    FILES_ERROR,
    RECORDS_IN,
    RECORDS_OUT,
    RECORDS_ERROR
  }

}
