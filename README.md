**USING CONSENSUS TO BUILD DISTRIBUTED SYSTEMS: Part 2**

[TOC]

# Overview #

### Goal ###
The goal of this assignment are to use consensus to build a fault-tolerant replicated datastore application using one of the following three options:

1. **Coordination server** (Zookeeper): A coordination protocol using Zookeeper as a logically centralized service accessible to all replicas;

2. **Replicated state machine** (GigaPaxos): GigaPaxos to encapsulate the application as an RSM;

3. **Custom protocol** (Custom): Your own coordination protocol possibly using a globally accessible logically centralized file system or database for coordination (an option analogous to #1 but not forcing you to use Zookeeper).

### Pre-requisites ###

1. Java is required; Linux strongly recommended but not necessary for any of the three options above;

2. Familiarity with the [replicated consistent (non-fault-tolerant) datastore programming assignment (PA2)](https://bitbucket.org/avenka/590cc/src/master/consistency/);

3. Completion of [consensus and RSM tutorials (Part 1)](https://bitbucket.org/distrsys/consensus-rsm-tutorials/src/master/README.md?mode=edit&at=master).

You are already familiar with the application environment andd background here having completed pre-requisite #2 above. The goal in this assignment is to make your previous replicated, consistent, non-fault-tolerant datastore fault-tolerant now using one of the three options in Goal above.

***

#  File organization #

### Your code files ###
Your work will use exactly one (not both) of the two files in the `faulttolerance` package as a starting point for your implementation:

1. [`MyDBReplicableApp`](https://bitbucket.org/distrsys/fault-tolerant-db/src/master/src/server/faulttolerance/MyDBReplicableAppGP.java) if using the GigaPaxos/RSM approach.

2. [`MyDBFaultTolerantServerZK`](https://bitbucket.org/distrsys/fault-tolerant-db/src/master/src/server/faulttolerance/MyDBFaultTolerantServerZK.java) otherwise.

You may create as many additional code files as needed in the `faulttolerance` package to support your implementation.
***

### Test code files ###
The test code (what used to be [`Grader`](https://bitbucket.org/avenka/590cc/src/master/consistency/test/Grader.java) for consistency-only tests) has evolved a fair bit for fault tolerance as follows:

1. [`GraderCommonSetup`](https://bitbucket.org/distrsys/fault-tolerant-db/src/master/test/GraderCommonSetup.java): The setup and teardown portions before and after tests common to both consistency and fault tolerance tests.

2. [`GraderConsistency`](https://bitbucket.org/distrsys/fault-tolerant-db/src/master/test/GraderConsistency.java): Tests identical to the old [`Grader`](https://bitbucket.org/avenka/590cc/src/master/consistency/test/Grader.java).

3. [`GraderFaultTolerance`](https://bitbucket.org/distrsys/fault-tolerant-db/src/master/test/GraderFaultTolerance.java): Setup and tests for testing fault tolerance, the primary testing focus of this assignment, inherited from `GraderCommonSetup`. The documentation of the tests in this class should be self-explanatory.

4. [`ServerFailureRecoveryManager`](https://bitbucket.org/distrsys/fault-tolerant-db/src/master/test/ServerFailureRecoveryManager.java): A class with sadistic (oops, I meant static) utiility methods that derive pleasure from killing and rebirthing your servers to stress-test fault tolerance.

***

# Constraints #
Your implementation must respect the following constraints, but these are not meant to be exhaustive, so if in doubt, ask.

1. Pick exactly one of the three high-level options in the Goal section above; do not mix multiple options.

2. If using the Zookeeper option, keep in mind the following (also documented at the top of [`MyDBFaultTolerantServerZK`](https://bitbucket.org/distrsys/fault-tolerant-db/src/master/src/server/faulttolerance/MyDBFaultTolerantServerZK.java)):
	1. You can not use any other form of coordination (like the file system or a database) between servers other than through Zookeeper. 
	2. The constraint above also precludes using the backend datastore itself for coordination, thus your server can not write to or read from any keyspace other than the one specified in its [constructor](https://bitbucket.org/distrsys/fault-tolerant-db/src/e4247b90b8e5fce088791db1d254ec4b217f66e1/src/server/faulttolerance/MyDBFaultTolerantServerZK.java#lines-60).
	3. You can assume that a single Zookeeper server is running on `localhost` at the [default port](https://bitbucket.org/distrsys/fault-tolerant-db/src/8d5714f278fd658e80f6f541a7e30cf0714e3500/src/server/faulttolerance/MyDBFaultTolerantServerZK.java#lines-49) when your submission is graded.
	
3. For all options, you can assume that a single Cassandra instance is running at the address specified in [MyDBFaultTolerantServerZK#main](https://bitbucket.org/distrsys/fault-tolerant-db/src/9a12b86469508854d641de52f19170ec6db712b5/src/server/faulttolerance/MyDBFaultTolerantServerZK.java#lines-107).

4. For all options, you can not maintain any in-memory or on-disk data structure containing more than [`MAX_LOG_SIZE (default 400)`](https://bitbucket.org/distrsys/fault-tolerant-db/src/9a12b86469508854d641de52f19170ec6db712b5/src/server/faulttolerance/MyDBFaultTolerantServerZK.java#lines-49) requests.
	
	***

# Getting Started #

Start by running your consistency-only replicated server (or using the [sample solution](https://bitbucket.org/distrsys/fault-tolerant-db/src/master/src/server/AVDBReplicatedServer.java) with [STUDENT_TESTING_MODE`=false`](https://bitbucket.org/distrsys/fault-tolerant-db/src/9a12b86469508854d641de52f19170ec6db712b5/test/GraderCommonSetup.java#lines-93)) by running GraderConsistency with [`TEST_FAULT_TOLERANCE`](https://bitbucket.org/distrsys/fault-tolerant-db/src/9a12b86469508854d641de52f19170ec6db712b5/test/GraderCommonSetup.java#lines-90) set to `false`. You should see the old consistency-only tests pass.

Next, revert back `TEST_FAULT_TOLERANCE` (and `STUDENT_TESTING_MODE` if modified) to its default true value and verify that some tests in [`GraderFaultTolerance`](https://bitbucket.org/distrsys/fault-tolerant-db/src/master/test/GraderFaultTolerance.java) fail.

From here on, you need to read the documentation of each test, understand why it's failing, and take it from there.

***

# Submission Instructions #

1. Submit a Bitbucket or Github repository forked from this repository to Gradescope keeping in mind the following:

    1. Only files in the `faulttolerance` package should contain your source changes. Any changes to any other source or test files will be ignored. 
	2. A design document (up to but not necessarily 3 pages long) 
		* explaining your design; 
		* explicitly noting how many tests passed in your testing; 
		* conceptual explanation for failing tests, i.e., understand and explain why the test is failing (possibly including sample output) and what you think you need to implement to bug-fix or complete your design.
	
***

# Post-release Corrections #
You are guinea pigs for this assignment in its current incarnation that is being test-driven for the first time, so some kinks will probably be discovered. Corrections or clarifications to this document or to the source code will be listed below.

# Tips, Troubleshooting, FAQs #
1. In addition to the detailed documentation, there are several handy tips in [`test/README.txt`](https://bitbucket.org/distrsys/fault-tolerant-db/src/master/test/README.txt) for playing with various testing and debugging options.
2. More based on your FAQs.

