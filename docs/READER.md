Performance Analyzer exposes a REST API that allows you to query numerous performance metrics for your cluster, including aggregations of those metrics, independent of the Java Virtual Machine (JVM). PerfTop is the default command line interface (CLI) for displaying those metrics. The two main components of performance analyzer are - 

* Performance analyzer plugin
* Performance analyzer app

# Performance Analyzer plugin

The performance analyzer plugin captures important events in Elasticsearch and
writes them to shared memory. These events are then analyzed separately by
a different process. Having separate processes, gives us more isolation between
Elasticsearch and the performance analyzer.

# Performance Analyzer Application

The performance analyzer application runs periodically and takes snapshots of
the data from shared memory.The application constantly polls a common shared 
directory for event data written by Elasticsearch plugin. The writer deletes
old data periodically, but some events like OSMetrics are overwritten every 5 seconds. 
Hence, the readerapplication runs every 2.5 seconds to make sure that no update from 
the writer is missed during normal operation. This avoids explicit synchronization between
the reader and writer processes.

The performance analyzer application is written in Java, and that allows us to
share code with the writer elasticsearch plugin. Java libraries like jdbc and jooq
made it very easy to generate sql programmatically.
The Java process by default takes up a lot of memory. We are running the
process with some specific options but there is room for further memory
optimization to enable the process to run on smaller instances.

Currently the application can process more up to a 100k events per second
on a single thread. If necessary, the metrics processing pipeline can be
modified to parse events concurrently. This will help support cases where we generate
more than 100k events per second.

## Metrics Processor 

This is the high level component that orchestrates all the work between
subcomponents. It schedules periodic jobs which is executed on a single
thread. The snapshots are held in a concurrent map and all customer requests to
fetch data are processed without any locks.

* How does it handle fairness between components?
The metrics processor processes events one after another. Hence, if processing
one particular type of event is slow then all metrics are delayed.

* What happens if it does not complete before the next run?
If metrics processing is not completed before the next run, then the metrics
processing for the next run is skipped. This leads to missing metrics for
a particular time period.

* What happens if one of the components is not processed and has an error?
All parsing errors should be handled and ignored when processing metrics.
Unknown errors will bubble up and cause the reader metrics processor to
restart.

## Metrics Parser

The metrics parser parses files in shared memory and writes the data
into inmemory snapshots in sqlite.

* How does it make it easier to add more parsers in the future?
Currently every parser is implemented independently. There will be utility
functions that will make it very easy to parse files and import data.

* How does it make it easier to batch load into inmemory snapshots?
We batch all updates form that need to be made to an inmemory snapshot and
apply it via a single update operation. This gives us a significant performance
boost.

* Can it be made more generic?
Going forward, we can specify paths and filenames to parse events and load them
into an inmemory snapshot.

## Snapshots

These are inmemory representation of data in sqlite tables. The snapshots allow
us to correlate data across different types of events.

* Why sqlite?
Sqlite is a very popular database that can be embedded into our process. Java
libraries for sqlite is also widely used and easy to onboard.

* write/read performance
From our tests we were able to do more than 100k writes per second and run
complex queries in milliseconds.

* Resource utilization?
The CPU Utilization of sqlite was minimal. The memory utilization of the entire
process after a few days processing metrics was constant around 400mb. This
needs further investigation on smaller instance types.

* How many snapshots?
We only need to hold snapshots in memory until the corresponding metrics are
written to the metrics database. After that snapshots can be deleted.

### Alignment

The plugin and the perf analyzer are separate applications and there is no
explicit co-ordination between them. The plugin emits periodic information like
cpu utilization for a given time period. To handle this scenario snapshots
expose an alignment function which can return the value across arbitrary time
windows. It does this by calculating time weighted average for metrics across
windows.

* Is there a simpler approach? Why is alignment even necessary?
The metrics written by the writer are not perfectly aligned. Additionally,
unpredictable events like garbage collection can add a skew to metric
publishing.

* What happens when there are missing snapshots?
No metrics are emitted.

* What happens if there are multiple snapshots in the same window?
The latest snapshot is considered. The correct approach is to weight the
metrics collected in each snapshot by the snapshot window size.

### Correlation

We can correlate data from different events emitted by Elasticsearch. A key
example for resource metrics is we get operating system metrics emitted every
5 seconds. This provides us with information such as cpu utilization and disk
io at a thread level. Additionally, Elasticsearch events like shardbulk and
shardsearch give us information about the tasks that were being carried out on
those threads. Using this information we can calculate the resources used at
a task level.

* How does sql make it easier to read and maintain.
Filters/aggregations and tables in sql make it easy to work with multiple rows
of data at the same time declaratively. SQL is also more concise compared to
for loops and thus easier to read and maintain.

## RequestMetrics

* Types of requests
We currently have different kinds of requests. There are HTTP requests and
shard level requests like shardBulk, shardQuery and shardFetch. We have an http
event that is emitted when a customer bulk request was received and another
event emitted when a customer bulk request was completed.

* Missing events
There are a few design decisions that need to be made around how we plan to
handle cases where events might be missing.
    * We ignore end events if start events are not emitted.
    * If we have a start event and a missing end event, we have a concept of
      expiry. Operations that exceed a certain threshold are marked stale and
      ignored.
    * There are certain operations which run from start to finish on a single
      thread. Shard operations are a good example of this. For such operations,
      we assume that the operation has ended if we see a new operation being
      executed on the same thread.

## OS/Node metrics
OS and node metrics are collected every 5 seconds and updated. Hence, we poll
for these metrics every 2.5 seconds to make sure we dont miss any updates.

* Multiple events within the same window
Sometimes because of GC pauses we might have more than one event in the same
window. In these cases we only consider the latest datapoint and ignore the
rest.

## MetricsDB

All the data is stored in a metrics database. We create a new database file
every 5 seconds. This helps us easily truncate old data. Additionally, the
client can query for any metrics of their choice and aggregate the metrics
across supported dimensions.
metrics=<metrics>&agg=<aggregations>&dim=<dimensions>

To achieve this kind of query, each metric is stored in its own table and the
final aggregation is done across metrics tables and returned to the client.

* Why this API? What were the other alternatives?
This API gives us the flexibility to query metrics across different dimensions.
This is not as complex as a full fledged query language like sql, but powerful
enough for metric aggregations.

The API currently does not support features like metricMath and filtering, but
these can be supported in the future.

* Why only current snapshot?
We have over 70 metrics with multiple dimensions. We dont want to store all
these metrics over time to be able to support aggregations. Instead, clients
who are interested in these metrics can frequently poll for them and
aggregate/store them in a format of their choice. This reduces the amount of
storage that needs to be available for storing metrics.

* Isnt it too expensive to load again? Why not just query the inmemory
  snapshots?

1) The metrics aggregation and processing logic is completely separate from the
format of the inmemory snapshots. We dont expect any major changes to this code
unless we add new features to the API.

2) MetricsDB files are written to disk and archived for future reference.
Hence, we can support a playback feature to understand cluster behavior across
time by fetching archived database files.

3) The number of metric snapshot we have to retain does not have to match the
number of snapshots. Snapshots have additional information like threadID and
threadName which are not available in the customer facing MetricsDB.

## MetricsEmitter

The metrics emitter queries in memory snapshots of data and then bulk loads
them into metricsDB. This helps us process more than 100k updates per second on
a single thread.

* How many emitters? Is it one per metric?
 A single emitter can emit multiple metrics. We currently have four emitters -
request, http, node and master. An emitter queries an inmemory snapshot and
then populates the result into the corresponding metricsDB tables.

* How quickly can we add new metrics? Do we really have to add a new emitter
  for every new metric or can it be done through configuration?
In the future we should be able to add new metrics and dimensions through
configuration instead of code.

