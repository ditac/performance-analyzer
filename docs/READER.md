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
the data from shared memory. There are three main components in the performance
analyzer application -

* How often does it run?
* Which language pros/cons
* Will it scale as we add more processors and events?

## Metrics Processor 

This is the high level component that orchestrates all the work between
subcomponents. It schedules periodic jobs which is executed on a single
thread. The snapshots are held in a concurrent map and all customer requests to
fetch data are processed without any locks.

* How does it handle fairness between components?
* What happens if it does not complete before the next run?
* What happens if one of the components is not processed and has an error?

## Metrics Parser

The metrics parser parses files in shared memory and writes the data
into inmemory snapshots in sqlite.

* How does it make it easier to add more parsers in the future?
* How does it make it easier to batch load into inmemory snapshots?
* Can it be made more generic?

## Snapshots

These are inmemory representation of data in sqlite tables. The snapshots allow
us to correlate data across different types of events. There are two main
operations that snapshots provide - 

* Why sqlite?
* write/read performance
* Resource utilization?
* How many snapshots?
* Synchronization between read/write?

### Alignment 

The plugin and the perf analyzer are separate applications and there is no
explicit co-ordination between them. The plugin emits periodic information like
cpu utilization for a given time period. To handle this scenario snapshots
expose an alignment function which can return the value across arbitrary time
windows. It does this by calculating time weighted average for metrics across
windows.

* Is there a simpler approach? Why is alignment even necessary?
* What happens when there are missing snapshots?
* What happens if there are multiple snapshots in the same window?

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

