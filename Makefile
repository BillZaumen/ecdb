#
# GNU Make file.
#

DATE = $(shell date -R)

#
# Set this if  'make install' should install its files into a
# user directory - useful for package systems that will grab
# all the files they see.  Setting this will allow a package
# to be built without requiring root permissions.
#
DESTDIR :=

JROOT := $(shell while [ ! -d src -a `pwd` != / ] ; do cd .. ; done ; pwd)

include VersionVars.mk

APPS_DIR = apps
MIMETYPES_DIR = mimetypes

#
# System directories (that contains JAR files, etc.)
#
SYS_BIN = /usr/bin
SYS_MANDIR = /usr/share/man
SYS_DOCDIR = /usr/share/doc/ecdb
SYS_MIMEDIR = /usr/share/mime
SYS_APPDIR = /usr/share/applications
SYS_ICON_DIR = /usr/share/icons/hicolor
SYS_APP_ICON_DIR = $(SYS_ICON_DIR)/scalable/$(APPS_DIR)
SYS_MIME_ICON_DIR =$(SYS_ICON_DIR)/scalable/$(MIMETYPES_DIR)
SYS_JARDIRECTORY = /usr/share/java
SYS_BZDEVDIR = /usr/share/bzdev

ICON_WIDTHS = 16 20 22 24 32 36 48 64 72 96 128 192 256

# Target JARDIRECTORY - where 'make install' actually puts the jar
# file (DESTDIR is not null when creating packages)
#
JARDIRECTORY = $(DESTDIR)$(SYS_JARDIRECTORY)
#
# JARDIRECTORY modified so that it can appear in a sed command
#
JARDIR=$(shell echo $(SYS_JARDIRECTORY) | sed  s/\\//\\\\\\\\\\//g)

# Other target directories

BIN = $(DESTDIR)$(SYS_BIN)
MANDIR = $(DESTDIR)$(SYS_MANDIR)
DOCDIR = $(DESTDIR)$(SYS_DOCDIR)
MIMEDIR = $(DESTDIR)$(SYS_MIMEDIR)
APPDIR = $(DESTDIR)$(SYS_APPDIR)
MIME_ICON_DIR = $(DESTDIR)$(SYS_MIME_ICON_DIR)
# Icon directory for applications
#
APP_ICON_DIR = $(DESTDIR)$(SYS_APP_ICON_DIR)
ICON_DIR = $(DESTDIR)$(SYS_ICON_DIR)

# Full path name of for where epts.desktop goes
#
# APPDIR = $(DESTDIR)/usr/share/applications

# Installed name of the icon to use for the ECDB application
#
SOURCEICON = icons/ecdb.svg
TARGETICON = ecdb.svg
TARGETICON_PNG = ecdb.png

JROOT_DOCDIR = $(JROOT)$(SYS_DOCDIR)
JROOT_JARDIR = $(JROOT)/BUILD
JROOT_MANDIR = $(JROOT)/man
JROOT_BIN = $(JROOT)/bin

EXTDIR = $(SYS_JARDIRECTORY)
EXTDIR_SED =  $(shell echo $(EXTDIR) | sed  s/\\//\\\\\\\\\\//g)
BZDEVDIR = $(DESTDIR)$(SYS_BZDEVDIR)
BZDEVDIR_SED = $(shell echo $(SYS_BZDEVDIR) | sed  s/\\//\\\\\\\\\\//g)

EXTLIBS1 = $(SYS_JARDIRECTORY)/javax.activation.jar
EXTLIBS2 = $(SYS_JARDIRECTORY)/javax.mail.jar

EXTLIBS = $(SYS_BZDEVDIR):$(EXTLIBS1):$(EXTLIBS2)

MANS = $(JROOT_MANDIR)/man1/ecdb.1.gz $(JROOT_MANDIR)/man5/ecdb.5.gz

ICONS = $(SOURCEICON) $(SOURCE_FILE_ICON) $(SOURCE_CFILE_ICON) \
	$(SOURCE_TCFILE_ICON)

ECDB_DIR = mods/org.bzdev.ecdb
ECDB_JDIR = $(ECDB_DIR)/org/bzdev/ecdb

ECDBJM_DIR = mods/org.bzdev.ecdb.javamail
ECDBJM_JDIR = $(ECDBJM_DIR)/org/bzdev/ecdb/javamail

ECDBDR_DIR = mods/org.bzdev.ecdb.dryrun
ECDBDR_JDIR = $(ECDBDR_DIR)/org/bzdev/ecdb/dryrun

JFILES = $(wildcard src/org.bzdev.ecdb/org/bzdev/ecdb/*.java)
PROPERTIES = src/org.bzdev.ecdb/org/bzdev/ecdb/Defaults.properties \
	src/org.bzdev.ecdb/org/bzdev/ecdb/sql.xml

JMFILES = $(wildcard src/org.bzdev.ecdb.javamail/org/bzdev/ecdb/javamail/*.java)
JDRFILES =$(wildcard src/org.bzdev.ecdb.dryrun/org/bzdev/ecdb/dryrun/*.java)

RESOURCES = $(wildcard src/org.bzdev.ecdb/org/bzdev/ecdb/*.tpl)
FILES = $(JFILES) $(PROPERTIES) $(RESOURCES)

PROGRAM = $(JROOT_BIN)/ecdb $(JROOT_JARDIR)/ecdb.jar 
ALL = $(PROGRAM) $(JROOT_JARDIR)/ecdb-javamail.jar \
	$(JROOT_JARDIR)/ecdb-dryrun.jar

#	ecdb.desktop $(MANS) $(JROOT_BIN)/ecdb

# program: $(JROOT_BIN)/ecdb $(JROOT_JARDIR)/ecdb-$(VERSION).jar

program: clean all

#
# Before using, set up a symbolic link for bzdevlib.jar in the ./jar directory.
# This is useful for testing that requires modifying files in bzdev-jlib.
#
testversion:
	make program EXTDIR=$(JROOT_JARDIR)

all: $(ALL)

#
# Use this to set up the links to the libraries for the jar subdirectory
# Needed for testing.
#
jardirlibs:
	(cd jar ; rm libbzdev-*.jar)
	ln -s $(EXTLIB1) $(EXTLIB2) $(EXTLIB3) $(EXTLIB4) \
		$(EXTLIB5) $(EXTLIB6) jar/


include MajorMinor.mk

org:
	ln -s src/org.bzdev.ecdb/org org

#
# use this rule to set up direct links to the provider directories:
# without symbolic links, the path names are rather long and awkward
# to type.
#
provider:
	mkdir -p provider
	ln -s ../src/org.bzdev.ecdb.javamail/org/bzdev/ecdb/javamail \
		provider/javamail
	ln -s ../src/org.bzdev.ecdb.dryrun/org/bzdev/ecdb/dryrun \
		provider/dryrun

setup: $(JROOT_JARDIR)/libbzdev-base.jar \
	$(JROOT_JARDIR)/libbzdev-desktop.jar \
	$(JROOT_JARDIR)/libbzdev-ejws.jar \
	$(JROOT_JARDIR)/libbzdev-obnaming.jar \
	$(JROOT_JARDIR)/javax.mail.jar \
	$(JROOT_JARDIR)/javax.activation.jar \
	$(JROOT_JARDIR)/derby.jar \

$(JROOT_JARDIR)/libbzdev-base.jar:
	(cd $(JROOT_JARDIR); ln -s $(SYS_BZDEVDIR)/libbzdev-base.jar . )

$(JROOT_JARDIR)/libbzdev-desktop.jar:
	(cd $(JROOT_JARDIR); ln -s $(SYS_BZDEVDIR)/libbzdev-desktop.jar . )

$(JROOT_JARDIR)/libbzdev-ejws.jar:
	(cd $(JROOT_JARDIR); ln -s $(SYS_BZDEVDIR)/libbzdev-ejws.jar . )

$(JROOT_JARDIR)/libbzdev-obnaming.jar:
	(cd $(JROOT_JARDIR); ln -s $(SYS_BZDEVDIR)/libbzdev-obnaming.jar . )

$(JROOT_JARDIR)/javax.mail.jar:
	(cd $(JROOT_JARDIR); ln -s $(SYS_JARDIRECTORY)javax.mail.jar . )

$(JROOT_JARDIR)/javax.activation.jar:
	(cd $(JROOT_JARDIR); ln -s $(SYS_JARDIRECTORY)javax.activation.jar . )

$(JROOT_JARDIR)/derby.jar:
	(cd $(JROOT_JARDIR); ln -s $(SYS_JARDIRECTORY)/derby.jar . )


# The action for this rule removes all the ecdb-*.jar files
# because the old ones would otherwise still be there and end up
# being installed.
#
$(JROOT_JARDIR)/ecdb.jar: $(FILES) $(PROPERTIES) $(RESOURCES) setup 
	rm -f $(JROOT_JARDIR)/ecdb.jar
	mkdir -p $(ECDB_JDIR)
	javac -Xlint:unchecked -Xlint:deprecation \
		-d mods/org.bzdev.ecdb -p $(JROOT_JARDIR) \
		src/org.bzdev.ecdb/module-info.java $(JFILES)
	cp $(PROPERTIES) $(ECDB_JDIR)
	cp $(RESOURCES) $(ECDB_JDIR)
	for i in $(ICON_WIDTHS) 512 ; do \
		inkscape -w $$i -e $(ECDB_JDIR)/ecdbicon$${i}.png \
		icons/ecdb.svg ; \
	done
	mkdir -p $(JROOT_JARDIR)
	rm -f $(JROOT_JARDIR)/ecdb.jar
	jar cfe $(JROOT_JARDIR)/ecdb.jar org.bzdev.ecdb.ECDB \
		-C $(ECDB_DIR) .

$(JROOT_JARDIR)/ecdb-javamail.jar: $(JMFILES) $(JROOT_JARDIR)/ecdb.jar
	mkdir -p $(ECDBJM_JDIR)
	rm -f $(JROOT_JARDIR)/ecdb-javamail.jar
	javac -Xlint:unchecked -Xlint:deprecation \
		-d mods/org.bzdev.ecdb.javamail	-p $(JROOT_JARDIR) \
		src/org.bzdev.ecdb.javamail/module-info.java $(JMFILES)
	mkdir -p $(ECDBJM_DIR)/META-INF/services
	echo org.bzdev.ecdb.javamail.JavamailSMTPAgentProvider > \
		$(ECDBJM_DIR)/META-INF/services/org.bzdev.ecdb.SMTPAgentSPI
	jar cf $(JROOT_JARDIR)/ecdb-javamail.jar -C $(ECDBJM_DIR) .

$(JROOT_JARDIR)/ecdb-dryrun.jar: $(JDRFILES) $(JROOT_JARDIR)/ecdb.jar
	mkdir -p $(ECDBDR_JDIR)
	rm -f $(JROOT_JARDIR)/ecdb-dryrun.jar
	javac -Xlint:unchecked -Xlint:deprecation \
		-d mods/org.bzdev.ecdb.dryrun	-p $(JROOT_JARDIR) \
		src/org.bzdev.ecdb.dryrun/module-info.java $(JDRFILES)
	mkdir -p $(ECDBDR_DIR)/META-INF/services
	echo org.bzdev.ecdb.dryrun.DryrunSMTPAgentProvider > \
		$(ECDBDR_DIR)/META-INF/services/org.bzdev.ecdb.SMTPAgentSPI
	jar cf $(JROOT_JARDIR)/ecdb-dryrun.jar -C $(ECDBDR_DIR) .



$(JROOT_BIN)/ecdb: ecdb.sh MAJOR MINOR \
		$(JROOT_JARDIR)/ecdb.jar
	(cd $(JROOT); mkdir -p $(JROOT_BIN))
	sed s/BZDEVDIR/$(BZDEVDIR_SED)/g ecdb.sh > $(JROOT_BIN)/ecdb
	chmod u+x $(JROOT_BIN)/ecdb
	for i in base desktop ejws ; do \
	if [ "$(DESTDIR)" = "" -a ! -f $(JROOT_JARDIR)/libbzdev-$$i.jar ] ; \
	then ln -sf $(EXTDIR)/libbzdev-$$i.jar \
		$(JROOT_JARDIR)/libbzdev-$$i.jar ; \
	fi ; done

$(JROOT_MANDIR)/man1/ecdb.1.gz: ecdb.1
	mkdir -p $(JROOT_MANDIR)/man1
	sed s/VERSION/$(VERSION)/g ecdb.1 | \
	gzip -n -9 > $(JROOT_MANDIR)/man1/ecdb.1.gz

$(JROOT_MANDIR)/man5/ecdb.5.gz: ecdb.5
	mkdir -p $(JROOT_MANDIR)/man5
	sed s/VERSION/$(VERSION)/g ecdb.5 | \
	gzip -n -9 > $(JROOT_MANDIR)/man5/ecdb.5.gz


clean:
	rm -fr mods
	rm -f $(JROOT_JARDIR)/ecdb.jar \

install: all
	install -d $(APP_ICON_DIR)
	install -d $(MIME_ICON_DIR)
	install -d $(MIMEDIR)
	install -d $(MIMEDIR)/packages
	install -d $(APPDIR)
	install -d $(BIN)
	install -d $(MANDIR)
	install -d $(BZDEVDIR)
	install -d $(MANDIR)/man1
	install -d $(MANDIR)/man5
	install -m 0644 -T $(SOURCEICON) $(APP_ICON_DIR)/$(TARGETICON)
	for i in $(ICON_WIDTHS) ; do \
		install -d $(ICON_DIR)/$${i}x$${i}/$(APPS_DIR) ; \
		inkscape -w $$i -e tmp.png $(SOURCEICON) ; \
		install -m 0644 -T tmp.png \
			$(ICON_DIR)/$${i}x$${i}/$(APPS_DIR)/$(TARGETICON_PNG); \
		rm tmp.png ; \
	done
	install -m 0644 -T mime/ecdb.xml $(MIMEDIR)/packages/ecdb.xml
	install -m 0644 -T $(SOURCE_FILE_ICON) \
		$(MIME_ICON_DIR)/$(TARGET_FILE_ICON)
	install -m 0644 -T $(SOURCE_CFILE_ICON) \
		$(MIME_ICON_DIR)/$(TARGET_CFILE_ICON)
	install -m 0644 -T $(SOURCE_TCFILE_ICON) \
		$(MIME_ICON_DIR)/$(TARGET_TCFILE_ICON)
	for i in $(ICON_WIDTHS) ; do \
	    install -d $(ICON_DIR)/$${i}x$${i}/$(MIMETYPES_DIR) ; \
	done;
	for i in $(ICON_WIDTHS) ; do \
	  inkscape -w $$i -e tmp.png $(SOURCE_FILE_ICON) ; \
	  install -m 0644 -T tmp.png \
	  $(ICON_DIR)/$${i}x$${i}/$(MIMETYPES_DIR)/$(TARGET_FILE_ICON_PNG); \
	  rm tmp.png ; \
	  inkscape -w $$i -e tmp.png $(SOURCE_CFILE_ICON) ; \
	  install -m 0644 -T tmp.png \
	  $(ICON_DIR)/$${i}x$${i}/$(MIMETYPES_DIR)/$(TARGET_CFILE_ICON_PNG); \
	  inkscape -w $$i -e tmp.png $(SOURCE_TCFILE_ICON) ; \
	  install -m 0644 -T tmp.png \
	  $(ICON_DIR)/$${i}x$${i}/$(MIMETYPES_DIR)/$(TARGET_TCFILE_ICON_PNG); \
	  rm tmp.png ; \
	done
	install -m 0644 $(JROOT_JARDIR)/ecdb.jar $(BZDEVDIR)
	install -m 0644 $(JROOT_JARDIR)/ecdb.policy $(BZDEVDIR)
	install -m 0755 $(JROOT_BIN)/ecdb $(BIN)
	install -m 0644 ecdb.desktop $(APPDIR)
	install -m 0644 $(JROOT_MANDIR)/man1/ecdb.1.gz $(MANDIR)/man1
	install -m 0644 $(JROOT_MANDIR)/man5/ecdb.5.gz $(MANDIR)/man5

install-links:
	for i in base desktop ejws ; do
	 [ -h $(BZDEVDIR)/libbzdev-$$i.jar ] || \
		ln -s $(EXTDIR)/libbzdev-$$i.jar $(BZDEVDIR)/libbzdev-$$i.jar; \
	done

uninstall:
	@rm $(MANDIR)/man1/ecdb.1.gz || echo ... rm ecdb.1.gz  FAILED
	@rm $(APPDIR)/ecdb.desktop || echo ... rm ecdb.desktop FAILED
	@rm $(BIN)/ecdb   || echo ... rm $(BIN)/ecdb FAILED
	@rm $(APP_ICON_DIR)/$(TARGETICON)  || echo ... rm $(TARGETICON) FAILED
	@for i in $(ICON_WIDTHS) ; do \
	   rm $(ICON_DIR)/$${i}x$${i}/$(APPS_DIR)/$(TARGETICON_PNG) \
		|| echo .. rm $(TARGETICON_PNG) from $${i}x$${i} FAILED; \
	done
	@rm $(MIME_ICON_DIR)/$(TARGET_FILE_ICON)  || \
		echo ... rm $(TARGET_FILE_ICON) FAILED
	@for i in $(ICON_WIDTHS) ; do \
	  rm $(ICON_DIR)/$${i}x$${i}/$(MIMETYPES_DIR)/$(TARGET_FILE_ICON_PNG) \
		|| echo rm $(TARGET_FILE_ICON_PNG) from $${i}x$${i} FAILED; \
	done
	@(cd $(MIMEDIR)/packages ; \
	 rm ecdb.xml || echo rm .../webail.xml FAILED)
	@rm $(JARDIRECTORY)/ecdb-$(VERSION).jar \
		|| echo ... rm ecdb-$(VERSION).jar FAILED
