# ECDB

ECDB is a simple database application for sending messages and
calendar appointments to people interested in some event. This
program can be run interactively using a GUI or as a command-line
program. Documentation is provided in two manual pages (for Part 1
and Part 5 of the manual) and can be viewed using the 'man' command.
Columns in tables displayed by the GUI correspond to various command-line
options.

Calendar appointments are sent when needed&mdash;when a new one is added or
an existing one is changed.  It is also possible to send messages, with
the messages represented by a template.

## Installation

To install, visit the
[Debian Repository](https://billzaumen.github.io/bzdev/) page and
follow the instructions for setting up /etc/apt to access that
repository, and install libbzdev-java and its dependencies.  Also
install libecdb-java, ecdb-javamail, ecdb-desktop, ecdb-doc,
and their dependencies.
You will also need the Debian packages gpg and libderby-java.
There are also instructions for other operating systems. The gpg
package is needed because the configuration file can include gpg-encrypted
entries to safely store database passwords.

## Source Code

The source code is located at

```
[https://github.com/BillZaumen/ecdb](https://github.com/BillZaumen/ecdb)
```

and contains an XML file
[sql.xml](https://github.com/BillZaumen/ecdb/blob/main/src/org.bzdev.ecdb/org/bzdev/ecdb/sql.xml)
that uses the XML-based format for
Java property files.  The keys match two patterns:

   - NAME&mdash;a simple identifier naming an entry, typically providing
     a default for use with any database.

   - NAME.DBTYPE&mdash;two identifiers separated by a period where NAME
     is the name of an entry of interest and DBTYPE is an identifier
     specifying the type of database being used.

for a given value of NAME, the key NAME.DBTYPE is tried first and if
that is missing, the key NAME is used. The DBTYPE identifiers can be
whatever a software developer wants, and this file can be easily
replaced so that ecdb can work with a preexisting data base (in that
case one will probably define views instead of tables).

## Screenshots

ECDB displays a control panel with a couple of short menus:

![ecdb main window](https://billzaumen.github.io/ecdb/ecdb.png)

The colored components near the bottom of the panel perform actions,
and the rest bring up views of database tables for editing.

## TO DO

More documentation is needed, plus support for more database systems.
For email, password authentication is currently supported but not OAuth or
OAuth 2.
