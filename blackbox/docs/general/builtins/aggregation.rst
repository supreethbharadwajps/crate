.. highlight:: psql
.. _aggregation:

===========
Aggregation
===========

.. rubric:: Table of Contents

.. contents::
   :local:

Introduction
============

An *aggregation function* operates on multiple values of the specified column
of your ``SELECT``. For example, ``SELECT avg(temperature)`` will return the
average value of the ``temperature`` column across all rows being considered.

You can apply aggregation functions to the results of a whole query, or to each
of the rows produced by a :ref:`sql_dql_group_by` query.

So for example, ``GROUP BY type, location`` will produce one result row for
every distinct combination of type and location. In this instance,
``avg(temperature)`` would return the average temperature for every grouped
row.

If you're not using `GROUP BY`, an aggregation function will collapse your
query result into one row, with one returned aggregation value.

For a tabulated summary of aggregation functions, see
:ref:`sql_dql_aggregation`.

``count``
=========

.. _aggregation-count-star:

``count(*)``
------------

This aggregation function simply returns the number of rows that match the
query.

``count(columName)`` is also possible, but currently only works on a primary key
column. The semantics are the same.

The return value is always of type ``long``.

::

    cr> select count(*) from locations;
    +----------+
    | count(*) |
    +----------+
    | 13       |
    +----------+
    SELECT 1 row in set (... sec)

``count(*)`` can also be used on group by queries::

    cr> select count(*), kind from locations group by kind order by kind asc;
    +----------+-------------+
    | count(*) | kind        |
    +----------+-------------+
    | 4        | Galaxy      |
    | 5        | Planet      |
    | 4        | Star System |
    +----------+-------------+
    SELECT 3 rows in set (... sec)

``count(columnName)``
---------------------

In contrast to the :ref:`aggregation-count-star` function the ``count``
function used with a column name as parameter will return the number of rows
with a non-``NULL`` value in that column.

Example::

    cr> select count(name), count(*), date from locations group by date
    ... order by count(name) desc, count(*) desc;
    +-------------+----------+---------------+
    | count(name) | count(*) | date          |
    +-------------+----------+---------------+
    | 7           | 8        | 1373932800000 |
    | 4           | 4        | 308534400000  |
    | 1           | 1        | 1367366400000 |
    +-------------+----------+---------------+
    SELECT 3 rows in set (... sec)

``count(distinct columnName)``
------------------------------

The ``count`` aggregation function also supports the ``distinct`` keyword. This
keyword changes the behaviour of the function so that it will only count the
number of distinct values in this column that are not ``NULL``::

    cr> select count(distinct kind), count(*), date
    ... from locations group by date
    ... order by count(distinct kind) desc, count(*) desc;
    +----------------------+----------+---------------+
    | count(DISTINCT kind) | count(*) | date          |
    +----------------------+----------+---------------+
    | 3                    | 8        | 1373932800000 |
    | 3                    | 4        | 308534400000  |
    | 1                    | 1        | 1367366400000 |
    +----------------------+----------+---------------+
     SELECT 3 rows in set (... sec)

::

    cr> select count(distinct kind) from locations;
    +----------------------+
    | count(DISTINCT kind) |
    +----------------------+
    | 3                    |
    +----------------------+
    SELECT 1 row in set (... sec)

``min``
=======

The ``min`` aggregation function returns the smallest value in a column that is
not ``NULL``. Its single argument is a column name and its return value is
always of the type of that column.

Example::

    cr> select min(position), kind
    ... from locations
    ... where name not like 'North %'
    ... group by kind order by min(position) asc, kind asc;
    +---------------+-------------+
    | min(position) | kind        |
    +---------------+-------------+
    | 1             | Planet      |
    | 1             | Star System |
    | 2             | Galaxy      |
    +---------------+-------------+
    SELECT 3 rows in set (... sec)

::

    cr> select min(date) from locations;
    +--------------+
    | min(date)    |
    +--------------+
    | 308534400000 |
    +--------------+
    SELECT 1 row in set (... sec)

``min`` returns ``NULL`` if the column does not contain any value but ``NULL``.
It is allowed on columns with primitive data types. On ``string`` columns it
will return the lexicographically smallest.

::

    cr> select min(name), kind from locations
    ... group by kind order by kind asc;
    +------------------------------------+-------------+
    | min(name)                          | kind        |
    +------------------------------------+-------------+
    | Galactic Sector QQ7 Active J Gamma | Galaxy      |
    |                                    | Planet      |
    | Aldebaran                          | Star System |
    +------------------------------------+-------------+
    SELECT 3 rows in set (... sec)

``max``
=======

It behaves exactly like ``min`` but returns the biggest value in a column that
is not ``NULL``.

Some Examples::

    cr> select max(position), kind from locations
    ... group by kind order by kind desc;
    +---------------+-------------+
    | max(position) | kind        |
    +---------------+-------------+
    | 4             | Star System |
    | 5             | Planet      |
    | 6             | Galaxy      |
    +---------------+-------------+
    SELECT 3 rows in set (... sec)

::

    cr> select max(position) from locations;
    +---------------+
    | max(position) |
    +---------------+
    | 6             |
    +---------------+
    SELECT 1 row in set (... sec)

::

    cr> select max(name), kind from locations
    ... group by kind order by max(name) desc;
    +-------------------+-------------+
    | max(name)         | kind        |
    +-------------------+-------------+
    | Outer Eastern Rim | Galaxy      |
    | Bartledan         | Planet      |
    | Altair            | Star System |
    +-------------------+-------------+
    SELECT 3 rows in set (... sec)

``sum``
=======

returns the sum of a set of numeric input values that are not ``NULL``.
Depending on the argument type a suitable return type is chosen. For float and
double argument types the return type is equal to the argument type. For byte,
short, integer and long the return type changes to long. If the range of long
values (-2^64 to 2^64-1) gets exceeded an ArithmeticException will be raised.

::

    cr> select sum(position), kind from locations
    ... group by kind order by sum(position) asc;
    +---------------+-------------+
    | sum(position) | kind        |
    +---------------+-------------+
    | 10            | Star System |
    | 13            | Galaxy      |
    | 15            | Planet      |
    +---------------+-------------+
    SELECT 3 rows in set (... sec)

::

    cr> select sum(position) as position_sum from locations;
    +--------------+
    | position_sum |
    +--------------+
    | 38           |
    +--------------+
    SELECT 1 row in set (... sec)

::

    cr> select sum(name), kind from locations group by kind order by sum(name) desc;
    SQLActionException[SQLParseException: Cannot cast name to type [double, float, long, integer, short, byte]]

``avg`` and ``mean``
====================

The ``avg`` and ``mean`` aggregation function returns the arithmetic mean, the
*average*, of all values in a column that are not ``NULL`` as a double value.
It accepts all numeric columns and timestamp columns as single argument. Using
``avg`` on other column types is not allowed.

Example::

    cr> select avg(position), kind from locations
    ... group by kind order by kind;
    +---------------+-------------+
    | avg(position) | kind        |
    +---------------+-------------+
    | 3.25          | Galaxy      |
    | 3.0           | Planet      |
    | 2.5           | Star System |
    +---------------+-------------+
    SELECT 3 rows in set (... sec)

``avg(distinct columnName)``
----------------------------

The ``avg`` aggregation function also supports the ``distinct`` keyword. This
keyword changes the behaviour of the function so that it will only average the
number of distinct values in this column that are not ``NULL``::

    cr> select avg(distinct position), count(*), date
    ... from locations group by date
    ... order by avg(distinct position) desc, count(*) desc;
    +------------------------+----------+---------------+
    | avg(DISTINCT position) | count(*) |          date |
    +------------------------+----------+---------------+
    |                    4.0 |        1 | 1367366400000 |
    |                    3.6 |        8 | 1373932800000 |
    |                    2.0 |        4 |  308534400000 |
    +------------------------+----------+---------------+
    SELECT 3 rows in set (... sec)

::

    cr> select avg(distinct position) from locations;
    +------------------------+
    | avg(DISTINCT position) |
    +------------------------+
    |                    3.5 |
    +------------------------+
    SELECT 1 row in set (... sec)

``geometric_mean``
==================

The ``geometric_mean`` aggregation function computes the geometric mean, a mean
for positive numbers. For details see: `Geometric Mean`_.

``geometric mean`` is defined on all numeric types and on timestamp. It always
returns double values. If a value is negative, all values were null or we got
no value at all ``NULL`` is returned. If any of the aggregated values is ``0``
the result will be ``0.0`` as well.

.. CAUTION::

    Due to java double precision arithmetic it is possible that any two
    executions of the aggregation function on the same data produce slightly
    differing results.

Example::

    cr> select geometric_mean(position), kind from locations
    ... group by kind order by kind;
    +--------------------------+-------------+
    | geometric_mean(position) | kind        |
    +--------------------------+-------------+
    |       2.6321480259049848 | Galaxy      |
    |       2.6051710846973517 | Planet      |
    |       2.213363839400643  | Star System |
    +--------------------------+-------------+
    SELECT 3 rows in set (... sec)

``variance``
============

The ``variance`` aggregation function computes the `Variance`_ of the set of
non-null values in a column. It is a measure about how far a set of numbers is
spread. A variance of ``0.0`` indicates that all values are the same.

``variance`` is defined on all numeric types and on timestamp. It returns a
double value. If all values were null or we got no value at all ``NULL`` is
returned.

Example::

    cr> select variance(position), kind from locations
    ... group by kind order by kind desc;
    +--------------------+-------------+
    | variance(position) | kind        |
    +--------------------+-------------+
    |             1.25   | Star System |
    |             2.0    | Planet      |
    |             3.6875 | Galaxy      |
    +--------------------+-------------+
    SELECT 3 rows in set (... sec)

.. CAUTION::

    Due to java double precision arithmetic it is possible that any two
    executions of the aggregation function on the same data produce slightly
    differing results.

``stddev``
==========

The ``stddev`` aggregation function computes the `Standard Deviation`_ of the
set of non-null values in a column. It is a measure of the variation of data
values. A low standard deviation indicates that the values tend to be near the
mean.

``stddev`` is defined on all numeric types and on timestamp. It always returns
double values. If all values were null or we got no value at all ``NULL`` is
returned.

Example::

    cr> select stddev(position), kind from locations
    ... group by kind order by kind;
    +--------------------+-------------+
    |   stddev(position) | kind        |
    +--------------------+-------------+
    | 1.920286436967152  | Galaxy      |
    | 1.4142135623730951 | Planet      |
    | 1.118033988749895  | Star System |
    +--------------------+-------------+
    SELECT 3 rows in set (... sec)

.. CAUTION::

    Due to java double precision arithmetic it is possible that any two
    executions of the aggregation function on the same data produce slightly
    differing results.

``percentile``
==============

The ``percentile`` aggregation function computes a `Percentile`_ over numeric
non-null values in a column.

Percentiles show the point at which a certain percentage of observed values
occur. For example, the 98th percentile is the value which is greater than 98%
of the observed values. The result is defined and computed as an interpolated
weighted average. According to that it allows the median of the input data to
be defined conveniently as the 50th percentile.

The function expects a single fraction or an array of fractions and a column
name. Independent of the input column data type the result of ``percentile``
always returns a double. If the value at the specified column is ``null`` the
row is ignored. Fractions must be double precision values between 0 and 1. When
supplied a single fraction, the function will return a single value
corresponding to the percentile of the specified fraction::

    cr> select percentile(position, 0.95), kind from locations
    ... group by kind order by kind;
    +----------------------------+-------------+
    | percentile(position, 0.95) | kind        |
    +----------------------------+-------------+
    |                        6.0 | Galaxy      |
    |                        5.0 | Planet      |
    |                        4.0 | Star System |
    +----------------------------+-------------+
    SELECT 3 rows in set (... sec)

When supplied an array of fractions, the function will return an array of
values corresponding to the percentile of each fraction specified::

    cr> select percentile(position, [0.0013, 0.9987]) as perc from locations;
    +------------+
    | perc       |
    +------------+
    | [1.0, 6.0] |
    +------------+
    SELECT 1 row in set (... sec)

When a query with ``percentile`` function won't match any rows then a null
result is returned.

To be able to calculate percentiles over a huge amount of data and to scale out
CrateDB calculates approximate instead of accurate percentiles. The algorithm
used by the percentile metric is called `TDigest`_. The accuracy/size trade-off
of the algorithm is defined by a single compression parameter which has a
constant value of ``100``. However, there are a few guidelines to keep in mind
in this implementation:

    - Extreme percentiles (e.g. 99%) are more accurate
    - For small sets percentiles are highly accurate
    - It's difficult to generalize the exact level of accuracy, as it depends
      on your data distribution and volume of data being aggregated

``arbitrary``
=============

The ``arbitrary`` aggregation function returns a single value of a column.
Which value it returns is not defined.

It accepts references to columns of all primitive types.

Using ``arbitrary`` on ``Object`` columns is not supported.

Its return type is the type of its parameter column and can be ``NULL`` if the
column contains ``NULL`` values.

Example::

    cr> select arbitrary(position) from locations;
    +---------------------+
    | arbitrary(position) |
    +---------------------+
    | ...                 |
    +---------------------+
    SELECT 1 row in set (... sec)

::

    cr> select arbitrary(name), kind from locations
    ... where name != ''
    ... group by kind order by kind desc;
    +-...-------------+-------------+
    | arbitrary(name) | kind        |
    +-...-------------+-------------+
    | ...             | Star System |
    | ...             | Planet      |
    | ...             | Galaxy      |
    +-...-------------+-------------+
    SELECT 3 rows in set (... sec)

An example use case is to group a table with many rows per user by ``user_id``
and get the ``username`` for every group, that means every user. This works as
rows with same ``user_id`` have the same ``username``.  This method performs
better than grouping on ``username`` as grouping on number types is generally
faster than on strings.  The advantage is that the ``arbitrary`` function does
very little to no computation as for example ``max`` aggregation function would
do.

.. _aggregation-hll-distinct:

``hyperloglog_distinct``
========================

.. note::

   The ``hyperloglog_distinct`` aggregate function is an :ref:`enterprise
   feature <enterprise_features>`.

The ``hyperloglog_distinct`` aggregate function calculates an approximate count
of distinct non-null values using the `HyperLogLog++`_ algorithm.

The return value data type is always a long.

The first argument can be a reference to a column of all
:ref:`sql_ddl_datatypes_primitives`. :ref:`sql_ddl_datatypes_compound` and
:ref:`sql_ddl_datatypes_geographic` are not supported.

The optional second argument defines the used ``precision`` for the
`HyperLogLog++`_ algorithm. This allows to trade memory for accuracy, valid
values are ``4`` to ``18``.
The default value for the ``precision`` which is used if the second argument is
left out is ``14``.

Examples::

    cr> select hyperloglog_distinct(position) from locations;
    +--------------------------------+
    | hyperloglog_distinct(position) |
    +--------------------------------+
    | 6                              |
    +--------------------------------+
    SELECT 1 row in set (... sec)

::

    cr> select hyperloglog_distinct(position, 4) from locations;
    +-----------------------------------+
    | hyperloglog_distinct(position, 4) |
    +-----------------------------------+
    | 6                                 |
    +-----------------------------------+
    SELECT 1 row in set (... sec)

Limitations
===========

 - ``DISTINCT`` is not supported with aggregations on :ref:`sql_joins`.
 - Prior to 2.0.0, unless documented, global aggregation functions are
   unsupported in combination with ``DISTINCT``.
 - Aggregation functions can only be applied to columns with a plain index,
   which is the default for all :ref:`primitive type
   <sql_ddl_datatypes_primitives>` columns. For more information, please refer
   to :ref:`sql_ddl_index_plain`.

.. _Geometric Mean: https://en.wikipedia.org/wiki/Mean#Geometric_mean_.28GM.29
.. _Variance: https://en.wikipedia.org/wiki/Variance
.. _Standard Deviation: https://en.wikipedia.org/wiki/Standard_deviation
.. _Percentile: https://en.wikipedia.org/wiki/Percentile
.. _TDigest: https://github.com/tdunning/t-digest/blob/master/docs/t-digest-paper/histo.pdf
.. _HyperLogLog++: https://research.google.com/pubs/pub40671.html
