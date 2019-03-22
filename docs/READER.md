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

There are two main stages in the metrics processor - 
* MetricsParser - This parses event files from shared memory and populates an
  inmemory database with the necessary data.
* MetricsEmitter - This stage queries the inmemory database for all the data
  and then populates the on-disk MetricDB.

The metrics processor processes all the events generated in the metricsParser
stage before proceeding to the metricsEmitter stage. Metrics are visible to the
client only after the metricsEmitter stage completes for all metrics. We
currently do not support individual pipelines per metric type. If metrics processing is
not completed before the next run, then the metrics processing for the next
run is scheduled only after the current run completes. This can lead to missing metrics
for a particular time period.

#### Error handling
All parsing errors should be handled and ignored when processing metrics.
Unknown errors will bubble up and cause the reader metrics processor to
restart.

### Metrics Parser

The metrics parser parses files in shared memory and writes the data
into inmemory snapshots in sqlite.Currently every parser is implemented independently.
We have a utility functions that make it easy to parse files and import data,
but need more work to be able to move to complete configuration based parsing.

After parsing, we batch all updates form that need to be made to an inmemory snapshot and
apply it via a single update operation. This gives us a significant performance
boost.

### Snapshots

These are inmemory representation of data in sqlite tables. The snapshots allow
us to correlate data across different types of events. We only need to hold snapshots in memory
until the corresponding metrics are written to the metrics database. After that snapshots can be deleted.

#### Sqlite
Sqlite is a very popular database that can be embedded into our process with minimal overhead.
Our current memory footprint with sqlite and java is around 400mb during heavy workloads, and we
should be able to reduce this further. We have also utilized JDBC and JOOQ
which is a java library for programmatically constructing SQL statements to
interact with sqlite. This helped us move fast and also make our code easy to read.

From our tests we were able to do more than 100k writes per second and run
complex queries in milliseconds.The CPU Utilization of sqlite was minimal. The memory utilization of the entire
process after a few days processing metrics was constant around 400mb. This
needs further investigation on smaller instance types.

#### RequestEvents

The Performance Analyzer plugin currently tracks two different types of events.
* HTTP requests - These are events emitted when we receive and respond to
  a customer request.
* Shard requests - These are internal requests that are generated from a single
  customer request. For example - a single http search request from the
customer on an index with multiple shards can result in multiple shardQuery and
shardFetch events.

In some edge cases, the writer plugin might emit a start event but not an end
event. For example if Elasticsearch crashes and restarts. In order to handle
such cases we take the following steps -
    * We ignore end events if start events are not emitted.
    * If we have a start event and a missing end event, we have a concept of
      expiry. Operations that exceed a certain threshold are marked stale and
      ignored.
    * There are certain operations which run from start to finish on a single
      thread. Shard operations are a good example of this. For such operations,
      we assume that the operation has ended, if we see a new operation being
      executed on the same thread.

#### OS/Node samples
OS and node statistics samples are collected every 5 seconds and updated.
The reader process, checks shared memory for changes every 2.5 seconds to make
sure we dont miss any updates.

#### Alignment

The plugin and the perf analyzer are separate applications and there is no
explicit co-ordination between them. The plugin emits periodic information like
cpu utilization for a given time period. But, the reader on the other hand will
be exposing metrics for a different period. For example - 
The plugin emits "CPU_Utilization" metric at 7, 12, 18 and 23. The reader
snapshots are at 5, 10, 15 and 20.

One approach to solve this problem is for the plugin to emit metrics at a very
specific time. Given that the plugin is emitting a large number of events and 
is also subjected to unpredictable events like GC pauses,
its very likely that there will be a skew in the time at which the plugin
metrics are emitted. Instead, if we can infer the value of metrics at a given time with
the time weighted average, we dont need explicit synchronization across the
reader and writer.

All snapshots expose an alignment function which will return the time weighted
average for metrics in the requested time range. If there are missing
snapshots, then the alignment function will return 0.

There is a corner case where we can have multiple samples for the time period
that need to be algined. In this case, we currently only consider the latest
sample. As an improvement we can consider the weighted average for all samples.

#### Correlation

We can correlate data between different events to generate fine grained metrics.
For example lets consider OS statistics, which are emitted by the writer plugin 
every 5 seconds. This provides us with information such as cpu and disk utilization
on a per thread basis. Additionally, Elasticsearch events like shardbulk and
shardsearch give us information about the tasks that were being carried out on
those threads. By correlating these two,we can calculate the resources used at
a task level.

On a large Elasticsearch cluster we often see hundreds of thousands of events
coupled with hundreds of threads.Filters/aggregations in sql make it easy to work with
such large amounts of data at the same time declaratively. SQL is also more concise compared to
for loops and thus easier to read and maintain.

### MetricsDB

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
enough for metric aggregations. The API currently does not support features like
metricMath and filtering, but these can be supported in the future.

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

### MetricsEmitter

The metrics emitter queries in memory snapshots of data and then bulk loads
them into metricsDB. This helps us process more than 100k updates per second on
a single thread. A single emitter can emit multiple metrics. We currently have 
four emitters - request, http, node and master. Every emitter queries an inmemory
snapshot and then populates the results into the corresponding metricsDB tables.

In the future we should be able to add new metrics and dimensions through
configuration instead of code.

## WebService
The performance analyzer application exposes a http webservice on port 9600.
 * API - Request format and response format. Metrics Units
 * Inter node communication.
 * Auth
 * Encryption

