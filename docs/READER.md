# Reader 
Performance Analyzer exposes a REST API that allows you to query numerous performance metrics for your cluster, including aggregations of those metrics, independent of the Java Virtual Machine (JVM). PerfTop is the default command line interface (CLI) for displaying those metrics. The two main components of performance analyzer are - 

* Performance analyzer plugin
* Performance analyzer app

# Performance Analyzer plugin

The performance analyzer plugin captures important events in Elasticsearch and
writes them to shared memory. These events are then analyzed separately by
a different process. Having separate processes, gives us more isolation between
Elasticsearch and the performance analyzer.

* Why separate process?
* How is synchronization done?
* What are the cons of a separate process?

# Performance Analyzer Application

The performance analyzer application runs periodically and takes snapshots of
the data from shared memory.

* How often does it run?
The application constantly polls a common shared directory for event data
written by Elasticsearch plugin. The writer deletes old data periodically, but
some events like OSMetrics are overwritten every 5 seconds. Hence, the reader
application runs every 2.5 seconds to make sure that no update from the writer
is missed during normal operation. This avoids explicit synchronization between
the reader and writer processes.

* Which language pros/cons
The performance analyzer application is written in Java, and that allows us to
share code with the writer elasticsearch plugin.
Java libraries like jdbc and jooq made it very easy to generate sql
programmatically.
The Java process by default takes up a lot of memory. We are running the
process with some specific options but there is room for further memory
optimization to enable the process to run on smaller instances.

* Will it scale as we add more processors and events?
Currently the application can process more up to a 100k events per second
on a single thread. If necessary, the metrics processing pipeline can be
modified to parse events concurrently.

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
The latest snapshot is considered.

### Correlation

We can correlate data from different events emitted by Elasticsearch. A key
example for resource metrics is we get operating system metrics emitted every
5 seconds. This provides us with information such as cpu utilization and disk
io at a thread level. Additionally, Elasticsearch events like shardbulk and
shardsearch give us information about the tasks that were being carried out on
those threads. Using this information we can calculate the resources used at
a task level.

* How does sql make it easier to read and maintain.
* Performance of joins

RequestMetrics

* Types of requests
* Missing events?
* Multiple events within the same window

## MetricsDB

All the data is stored in a metrics database. We create a new database file
every 5 seconds. This helps us easily truncate old data. Additionally, the
client can query for any metrics of their choice and aggregate the metrics
across supported dimensions.
metrics=<metrics>&agg=<aggregations>&dim=<dimensions>

To achieve this kind of query, each metric is stored in its own table and the
final aggregation is done across metrics tables and returned to the client.

* Why this API? What were the other alternatives?
* Why only current snapshot?
* Future improvements
* Isnt it too expensive to load again? Why not just query the inmemory
  snapshots?

## MetricsEmitter

The metrics emitter queries in memory snapshots of data and then bulk loads
them into metricsDB. This helps us process more than 100k updates per second on
a single thread.

* How many emitters? Is it one per metric?
* How quickly can we add new metrics? Do we really have to add a new emitter
  for every new metric or can it be done through configuration?

