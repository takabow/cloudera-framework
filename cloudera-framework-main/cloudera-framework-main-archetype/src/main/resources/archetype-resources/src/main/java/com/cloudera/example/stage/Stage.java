package com.cloudera.example.stage;

import java.io.IOException;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.TimeZone;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Type;
import org.apache.avro.mapred.AvroKey;
import org.apache.avro.mapred.AvroValue;
import org.apache.avro.mapreduce.AvroJob;
import org.apache.avro.mapreduce.AvroKeyOutputFormat;
import org.apache.avro.mapreduce.AvroMultipleOutputs;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputCommitter;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.LazyOutputFormatNoCheck;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.example.Constants;
import com.cloudera.example.model.Record;
import com.cloudera.example.model.RecordCounter;
import com.cloudera.example.model.RecordKey;
import com.cloudera.example.model.RecordType;
import com.cloudera.framework.main.common.Driver;

/**
 * Stage driver, take a dataset of various formats (command and tab field
 * separated UTF8/16, new line record delimietered text files) with a known
 * naming scheme and stage their records into an ingest timestamp partitioned
 * staging set, written as sequence files with timestamp key and original record
 * format UTF16 values.
 */
public class Stage extends Driver {

  // TODO Provide implementation

  protected static final String OUTPUT_TEXT = "text";
  protected static final String OUTPUT_AVRO = "avro";

  private static final Logger LOG = LoggerFactory.getLogger(Stage.class);

  private Set<Path> inputPaths;
  private Path outputPath;

  public Stage() {
    super();
  }

  public Stage(Configuration confguration) {
    super(confguration);
  }

  @Override
  public String description() {
    return "Clense my dataset";
  }

  @Override
  public String[] options() {
    return new String[] {};
  }

  @Override
  public String[] parameters() {
    return new String[] { "input-path", "output-path" };
  }

  @Override
  public void reset() {
    super.reset();
    for (RecordCounter counter : RecordCounter.values()) {
      incrementCounter(Stage.class.getCanonicalName(), counter, 0);
    }
  }

  @Override
  public int prepare(String... arguments) throws Exception {
    if (arguments == null || arguments.length != 2) {
      throw new Exception("Invalid number of arguments");
    }
    FileSystem hdfs = FileSystem.newInstance(getConf());
    Path inputPath = new Path(arguments[0]);
    if (!hdfs.exists(inputPath)) {
      throw new Exception("Input path [" + inputPath + "] does not exist");
    }
    if (LOG.isInfoEnabled()) {
      LOG.info("Input path [" + inputPath + "] validated");
    }
    inputPaths = new HashSet<Path>();
    RemoteIterator<LocatedFileStatus> inputPathsIterator = hdfs.listFiles(inputPath, true);
    while (inputPathsIterator.hasNext()) {
      inputPaths.add(inputPathsIterator.next().getPath().getParent());
    }
    outputPath = new Path(arguments[1]);
    hdfs.mkdirs(outputPath.getParent());
    if (hdfs.exists(outputPath)) {
      throw new Exception("Output path [" + inputPath + "] already exists");
    }
    if (LOG.isInfoEnabled()) {
      LOG.info("Output path [" + outputPath + "] validated");
    }
    return RETURN_SUCCESS;
  }

  @Override
  public int execute() throws Exception {
    Job job = Job.getInstance(getConf());
    job.setJobName(getClass().getSimpleName());
    job.setJarByClass(Stage.class);
    for (Path inputPath : inputPaths) {
      FileInputFormat.addInputPath(job, inputPath);
    }
    FileOutputFormat.setOutputPath(job, outputPath);
    LazyOutputFormatNoCheck.setOutputFormatClass(job, TextOutputFormat.class);
    job.getConfiguration().set(FileOutputCommitter.SUCCESSFUL_JOB_OUTPUT_DIR_MARKER, Boolean.FALSE.toString());
    job.setSortComparatorClass(RecordKey.RecordKeyComparator.class);
    job.setMapperClass(Mapper.class);
    job.setInputFormatClass(TextInputFormat.class);
    job.setMapOutputKeyClass(RecordKey.class);
    job.setMapOutputValueClass(AvroValue.class);
    AvroJob.setMapOutputValueSchema(job, Record.getClassSchema());
    job.setReducerClass(Reducer.class);
    job.setNumReduceTasks(inputPaths.size());
    AvroJob.setOutputKeySchema(job, Schema.create(Type.NULL));
    AvroJob.setOutputValueSchema(job, Record.getClassSchema());
    MultipleOutputs.addNamedOutput(job, OUTPUT_TEXT, TextOutputFormat.class, NullWritable.class, Text.class);
    AvroMultipleOutputs.addNamedOutput(job, OUTPUT_AVRO, AvroKeyOutputFormat.class, Record.getClassSchema());
    boolean jobSuccess = job.waitForCompletion(LOG.isInfoEnabled());
    importCounters(job, new RecordCounter[] { RecordCounter.RECORDS_MALFORMED, RecordCounter.RECORDS_DUPLICATE,
        RecordCounter.RECORDS_CLEANSED, RecordCounter.RECORDS });
    return jobSuccess ? RETURN_SUCCESS : RETURN_FAILURE_RUNTIME;
  }

  /**
   * Mapper, parse both comma and tab separated values into {@link Record
   * Records} keyed by {@link RecordKey RecordKeys}.<br>
   * <br>
   * Note this class is not thread-safe but is jvm-reuse-safe, reusing objects
   * where possible.
   */
  private static class Mapper
      extends org.apache.hadoop.mapreduce.Mapper<LongWritable, Text, RecordKey, AvroValue<Record>> {

    private final Record EMPTY_RECORD = new Record();

    private String splitFileName;
    private String splitFilePathRelative;

    private RecordKey recordKey = new RecordKey();
    private Record recordValue = new Record();
    private AvroValue<Record> recordValueWrapped = new AvroValue<Record>();

    @Override
    protected void setup(
        org.apache.hadoop.mapreduce.Mapper<LongWritable, Text, RecordKey, AvroValue<Record>>.Context context)
            throws IOException, InterruptedException {
      Path splitFilePath = ((FileSplit) context.getInputSplit()).getPath();
      splitFileName = splitFilePath.getName();
      splitFilePathRelative = new StringBuilder(128).append(splitFilePath.getParent().getParent().getParent().getName())
          .append(Path.SEPARATOR_CHAR).append(splitFilePath.getParent().getParent().getName())
          .append(Path.SEPARATOR_CHAR).append(splitFilePath.getParent().getName()).append(Path.SEPARATOR_CHAR)
          .append(splitFileName).toString();
    }

    @Override
    protected void map(LongWritable key, Text value,
        org.apache.hadoop.mapreduce.Mapper<LongWritable, Text, RecordKey, AvroValue<Record>>.Context context)
            throws IOException, InterruptedException {
      String valueString = value.toString();
      boolean recordValid = RecordType.deserialise(recordValue, splitFileName, valueString);
      recordKey.setValid(recordValid);
      recordKey.setPath(splitFilePathRelative);
      recordKey.setSource(recordValid ? null : valueString);
      recordKey.setHash(recordValid ? recordValue.hashCode() : valueString.hashCode());
      recordValueWrapped.datum(recordValid ? recordValue : EMPTY_RECORD);
      context.write(recordKey, recordValueWrapped);
    }

  }

  /**
   * Reducer, write out cleansed, partitioned {@link Record Records}, filtering
   * malformed and duplicate records as necessary.<br>
   * <br>
   * Note this class is not thread-safe but is jvm-reuse-safe, reusing objects
   * where possible.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static class Reducer
      extends org.apache.hadoop.mapreduce.Reducer<RecordKey, AvroValue<Record>, NullWritable, AvroValue<Record>> {

    private static final String PARTITION_YEAR = "year=";
    private static final String PARTITION_MONTH = "month=";
    private static final String PARTITION_FILE = "data";

    private MultipleOutputs multipleOutputs;
    private AvroMultipleOutputs multipleOutputsAvro;

    private Text source = new Text();
    private AvroKey<Record> record = new AvroKey<Record>();
    private StringBuilder string = new StringBuilder(512);
    private Set<AvroValue<Record>> records = new HashSet<AvroValue<Record>>(32);
    private Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));

    @Override
    protected void setup(
        org.apache.hadoop.mapreduce.Reducer<RecordKey, AvroValue<Record>, NullWritable, AvroValue<Record>>.Context context)
            throws IOException, InterruptedException {
      multipleOutputs = new MultipleOutputs(context);
      multipleOutputsAvro = new AvroMultipleOutputs(context);
    }

    @Override
    public void cleanup(Context context) throws IOException, InterruptedException {
      multipleOutputs.close();
      multipleOutputsAvro.close();
    }

    @Override
    protected void reduce(RecordKey key, Iterable<AvroValue<Record>> values,
        org.apache.hadoop.mapreduce.Reducer<RecordKey, AvroValue<Record>, NullWritable, AvroValue<Record>>.Context context)
            throws IOException, InterruptedException {
      records.clear();
      Iterator<AvroValue<Record>> valuesIterator = values.iterator();
      while (valuesIterator.hasNext()) {
        AvroValue<Record> record = valuesIterator.next();
        context.getCounter(RecordCounter.RECORDS).increment(1);
        if (key.isValid()) {
          RecordCounter counter = records.add(record) ? RecordCounter.RECORDS_CLEANSED
              : RecordCounter.RECORDS_DUPLICATE;
          this.record.datum(record.datum());
          calendar.setTimeInMillis(record.datum().getMyTimestamp());
          string.setLength(0);
          multipleOutputsAvro.write(OUTPUT_AVRO, this.record, NullWritable.get(),
              string
                  .append(counter.equals(RecordCounter.RECORDS_CLEANSED)
                      ? Constants.DIR_DS_MYDATASET_PROCESSED_CLEANSED_AVRO_RELATIVE
                      : Constants.DIR_DS_MYDATASET_PROCESSED_DUPLICATE_AVRO_RELATIVE)
                  .append(Path.SEPARATOR_CHAR).append(PARTITION_YEAR).append(calendar.get(Calendar.YEAR))
                  .append(Path.SEPARATOR_CHAR).append(PARTITION_MONTH).append(calendar.get(Calendar.MONTH) + 1)
                  .append(Path.SEPARATOR_CHAR).append(PARTITION_FILE).toString());
          context.getCounter(counter).increment(1);
        } else {
          source.set(key.getSource());
          string.setLength(0);
          multipleOutputs.write(OUTPUT_TEXT, NullWritable.get(), source,
              string.append(Constants.DIR_DS_MYDATASET_MALFORMED).append(Path.SEPARATOR_CHAR).append(key.getPath())
                  .toString());
          context.getCounter(RecordCounter.RECORDS_MALFORMED).increment(1);
        }
      }
    }
  }

}