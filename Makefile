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

JAVA_VERSION = 11

include VersionVars.mk

#
# Set DARKMODE to --darkmode to turn on dark mode.
#
DARKMODE =

APPS_DIR = apps
MIMETYPES_DIR = mimetypes

#
# System directories (that contains JAR files, etc.)
#
SYS_BIN = /usr/bin
SYS_MANDIR = /usr/share/man
SYS_DOCDIR = /usr/share/doc/ecdb-java
SYS_API_DOCDIR = /usr/share/doc/ecdb-doc
SYS_JAVADOCS = $(SYS_API_DOCDIR)/api
SYS_MIMEDIR = /usr/share/mime
SYS_APPDIR = /usr/share/applications
SYS_ICON_DIR = /usr/share/icons/hicolor
SYS_POPICON_DIR = /usr/share/icons/Pop
SYS_APP_ICON_DIR = $(SYS_ICON_DIR)/scalable/$(APPS_DIR)
SYS_APP_POPICON_DIR = $(SYS_POPICON_DIR)/scalable/$(APPS_DIR)
SYS_MIME_ICON_DIR =$(SYS_ICON_DIR)/scalable/$(MIMETYPES_DIR)
SYS_POPICON_DIR = /usr/share/icons/Pop
SYS_MIME_POPICON_DIR =$(SYS_POPICON_DIR)/scalable/$(MIMETYPES_DIR)

SYS_JARDIRECTORY = /usr/share/java
SYS_BZDEVDIR = /usr/share/bzdev
SYS_ECDBDIR = /usr/share/ecdb

ICON_WIDTHS = 8 16 20 22 24 32 36 48 64 72 96 128 192 256 512
ICON_WIDTHS2x = 16 24 32 48 64 128 256

POPICON_WIDTHS = 8 16 24 32 48 64 128 256
POPICON_WIDTHS2x = 8 16 24 32 48 64 128 256

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
API_DOCDIR = $(DESTDIR)$(SYS_API_DOCDIR)
JAVADOCS = $(DESTDIR)$(SYS_JAVADOCS)
MIMEDIR = $(DESTDIR)$(SYS_MIMEDIR)
APPDIR = $(DESTDIR)$(SYS_APPDIR)
MIME_ICON_DIR = $(DESTDIR)$(SYS_MIME_ICON_DIR)
POPICON_DIR = $(DESTDIR)$(SYS_POPICON_DIR)
MIME_POPICON_DIR=$(DESTDIR)$(SYS_MIME_POPICON_DIR)
# Icon directory for applications
#
APP_ICON_DIR = $(DESTDIR)$(SYS_APP_ICON_DIR)
APP_POPICON_DIR = $(DESTDIR)$(SYS_APP_POPICON_DIR)
ICON_DIR = $(DESTDIR)$(SYS_ICON_DIR)

# Full path name of for where epts.desktop goes
#
# APPDIR = $(DESTDIR)/usr/share/applications

# Installed name of the icon to use for the ECDB application
#
SOURCEICON = icons/ecdb.svg
SOURCE_FILE_ICON = icons/ecdbconf.svg
TARGETICON = ecdb.svg
TARGETICON_PNG = ecdb.png
TARGET_FILE_ICON = application-x.ecdb-config.svg
TARGET_FILE_ICON_PNG = application-x.ecdb-config.png

JROOT_DOCDIR = $(JROOT)$(SYS_DOCDIR)
JROOT_JARDIR = $(JROOT)/BUILD
JROOT_MANDIR = $(JROOT)/man
JROOT_BIN = $(JROOT)/bin

EXTDIR = $(SYS_JARDIRECTORY)
EXTDIR_SED =  $(shell echo $(EXTDIR) | sed  s/\\//\\\\\\\\\\//g)
BZDEVDIR = $(DESTDIR)$(SYS_BZDEVDIR)
BZDEVDIR_SED = $(shell echo $(SYS_BZDEVDIR) | sed  s/\\//\\\\\\\\\\//g)
ECDBDIR = $(DESTDIR)$(SYS_ECDBDIR)
ECDBDIR_SED = $(shell echo $(SYS_ECDBDIR) | sed  s/\\//\\\\\\\\\\//g)

EXTJARS = javax.activation.jar javax.mail.jar
EXTLIBS1 = $(SYS_JARDIRECTORY)/javax.activation.jar
EXTLIBS2 = $(SYS_JARDIRECTORY)/javax.mail.jar

EXTLIBS = $(SYS_BZDEVDIR):$(EXTLIBS1):$(EXTLIBS2)

MANS = $(JROOT_MANDIR)/man1/ecdb.1.gz $(JROOT_MANDIR)/man5/ecdb.5.gz

ICONS = $(SOURCEICON) $(SOURCE_FILE_ICON)

ECDB_SDIR = src/org.bzdev.ecdb
ECDB_JSDIR = $(ECDB_SDIR)/org/bzdev/ecdb

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
ALL = $(SETUP) $(PROGRAM) $(JROOT_JARDIR)/ecdb-javamail.jar \
	$(JROOT_JARDIR)/ecdb-dryrun.jar \
	ecdb.desktop $(MANS) $(JROOT_BIN)/ecdb

# program: $(JROOT_BIN)/ecdb $(JROOT_JARDIR)/ecdb-$(VERSION).jar

all: $(ALL)

program: clean all

#
# Before using, set up a symbolic link for bzdevlib.jar in the ./jar directory.
# This is useful for testing that requires modifying files in bzdev-jlib.
#
testversion:
	make program EXTDIR=$(JROOT_JARDIR)


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

SETUP = $(JROOT_JARDIR)/libbzdev-base.jar \
	$(JROOT_JARDIR)/libbzdev-desktop.jar \
	$(JROOT_JARDIR)/libbzdev-ejws.jar \
	$(JROOT_JARDIR)/libbzdev-obnaming.jar \
	$(JROOT_JARDIR)/javax.mail.jar \
	$(JROOT_JARDIR)/javax.activation.jar \
	$(JROOT_JARDIR)/derby.jar \

setup: $(SETUP)

$(JROOT_JARDIR)/libbzdev-base.jar:
	mkdir -p $(JROOT_JARDIR)
	(cd $(JROOT_JARDIR); ln -s $(SYS_BZDEVDIR)/libbzdev-base.jar . )

$(JROOT_JARDIR)/libbzdev-desktop.jar:
	mkdir -p $(JROOT_JARDIR)
	(cd $(JROOT_JARDIR); ln -s $(SYS_BZDEVDIR)/libbzdev-desktop.jar . )

$(JROOT_JARDIR)/libbzdev-ejws.jar:
	mkdir -p $(JROOT_JARDIR)
	(cd $(JROOT_JARDIR); ln -s $(SYS_BZDEVDIR)/libbzdev-ejws.jar . )

$(JROOT_JARDIR)/libbzdev-obnaming.jar:
	mkdir -p $(JROOT_JARDIR)
	(cd $(JROOT_JARDIR); ln -s $(SYS_BZDEVDIR)/libbzdev-obnaming.jar . )

$(JROOT_JARDIR)/javax.mail.jar:
	mkdir -p $(JROOT_JARDIR)
	(cd $(JROOT_JARDIR); ln -s $(SYS_JARDIRECTORY)/javax.mail.jar . )

$(JROOT_JARDIR)/javax.activation.jar:
	mkdir -p $(JROOT_JARDIR)
	(cd $(JROOT_JARDIR); ln -s $(SYS_JARDIRECTORY)/javax.activation.jar . )

$(JROOT_JARDIR)/derby.jar:
	mkdir -p $(JROOT_JARDIR)
	(cd $(JROOT_JARDIR); ln -s $(SYS_JARDIRECTORY)/derby.jar . )


DIAGRAMS = $(ECDB_JSDIR)/doc-files/attendees.png \
	$(ECDB_JSDIR)/doc-files/owner.png \
	$(ECDB_JSDIR)/doc-files/timing.png \
	$(ECDB_JSDIR)/doc-files/user.png

diagrams: $(DIAGRAMS)

$(ECDB_JSDIR)/doc-files/attendees.png: diagrams/attendees.dia
	mkdir -p $(ECDB_SDIR)/org/bzdev/ecdb/doc-files
	dia -s 700x -e $@ $<

$(ECDB_JSDIR)/doc-files/owner.png: diagrams/owner.dia
	mkdir -p $(ECDB_SDIR)/org/bzdev/ecdb/doc-files
	dia -s 700x -e $@ $<

$(ECDB_JSDIR)/doc-files/timing.png: diagrams/timing.dia
	mkdir -p $(ECDB_SDIR)/org/bzdev/ecdb/doc-files
	dia -s 700x -e $@ $<

$(ECDB_JSDIR)/doc-files/user.png: diagrams/user.dia
	mkdir -p $(ECDB_SDIR)/org/bzdev/ecdb/doc-files
	dia -s 700x -e $@ $<


# The action for this rule removes all the ecdb-*.jar files
# because the old ones would otherwise still be there and end up
# being installed.
#
$(JROOT_JARDIR)/ecdb.jar: $(FILES) $(PROPERTIES) $(RESOURCES) $(SETUP)
	rm -f $(JROOT_JARDIR)/ecdb.jar
	mkdir -p $(ECDB_JDIR)
	javac -Xlint:unchecked -Xlint:deprecation \
		-d mods/org.bzdev.ecdb -p $(JROOT_JARDIR) \
		src/org.bzdev.ecdb/module-info.java $(JFILES)
	cp $(PROPERTIES) $(ECDB_JDIR)
	cp $(RESOURCES) $(ECDB_JDIR)
	for i in $(ICON_WIDTHS) ; do \
		inkscape -w $$i \
		--export-filename=$(ECDB_JDIR)/ecdbicon$${i}.png \
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
	sed s/ECDBDIR/$(ECDBDIR_SED)/g ecdb.sh > $(JROOT_BIN)/ecdb
	chmod u+x $(JROOT_BIN)/ecdb
	for i in base desktop ejws ; do \
	if [ "$(DESTDIR)" = "" -a ! -f $(JROOT_JARDIR)/libbzdev-$$i.jar ] ; \
	then ln -sf $(EXTDIR)/libbzdev-$$i.jar \
		$(JROOT_JARDIR)/libbzdev-$$i.jar ; \
	fi ; done

$(JROOT_MANDIR)/man1/ecdb.1.gz: ecdb.1
	mkdir -p $(JROOT_MANDIR)/man1
	sed s/VERSION/$(VERSION)/g ecdb.1 | \
	sed s/DATE/"`date +'%B %Y'`"/g | \
	gzip -n -9 > $(JROOT_MANDIR)/man1/ecdb.1.gz

$(JROOT_MANDIR)/man5/ecdb.5.gz: ecdb.5
	mkdir -p $(JROOT_MANDIR)/man5
	sed s/VERSION/$(VERSION)/g ecdb.5 | \
	sed s/DATE/"`date +'%B %Y'`"/g | \
	gzip -n -9 > $(JROOT_MANDIR)/man5/ecdb.5.gz


clean:
	rm -fr mods man bin
	rm -f $(JROOT_JARDIR)/ecdb.jar


#
# ------------------  JAVADOCS -------------------
#
JROOT_JAVADOCS = $(JROOT)/BUILD/api

jdclean:
	rm -rf BUILD/api

javadocs: $(JROOT_JAVADOCS)/index.html

JDOC_MODULES= org.bzdev.ecdb

$(JROOT_JAVADOCS)/index.html: $(JROOT_JARDIR)/ecdb.jar $(DIAGRAMS) \
		$(ECDB_JSDIR)/doc-files/description.html \
		src/overview.html src/description.html
	rm -rf $(JROOT_JAVADOCS)
	mkdir -p $(JROOT_JAVADOCS)
	styleoption=`[ -z "$(DARKMODE)" ] && echo \
		|| echo --main-stylesheet stylesheet.css`; \
	javadoc -d $(JROOT_JAVADOCS) --module-path BUILD \
		$$styleoption \
		--module-source-path src \
		--add-modules $(JDOC_MODULES) \
		-link file:///usr/share/doc/openjdk-$(JAVA_VERSION)-doc/api \
		-link file:///usr/share/doc/libbzdev-doc/api \
		-overview src/overview.html \
		--module $(JDOC_MODULES) | grep -E -v -e '^Generating' \
		| grep -E -v -e '^Copying file'
	mkdir -p $(JROOT_JAVADOCS)/doc-files
	cp src/description.html $(JROOT_JAVADOCS)/doc-files/description.html
	cp src/org.bzdev.ecdb/org/bzdev/ecdb/sql.xml \
		$(JROOT_JAVADOCS)/doc-files/sql.xml.txt
	for i in $(MOD_IMAGES) ; \
	    do cp src/doc-files/$$i $(JROOT_JAVADOCS)/doc-files ; done


#
# ---------------------INSTALL -------------------
#
install-lib: all
	install -d $(ECDBDIR)
	install -d $(JARDIRECTORY)
	install -m 0644 $(JROOT_JARDIR)/ecdb.jar \
		$(JARDIRECTORY)/ecdb-$(VERSION).jar
	install -m 0644 $(JROOT_JARDIR)/ecdb-dryrun.jar \
		$(JARDIRECTORY)/ecdb-dryrun-$(VERSION).jar;

install-javamail: all
	install -d $(JARDIRECTORY)
	install -m 0644 $(JROOT_JARDIR)/ecdb-javamail.jar \
		$(JARDIRECTORY)/ecdb-javamail-$(VERSION).jar;

install-links:
	install -d $(JARDIRECTORY)
	install -d $(ECDBDIR)
	for i in base obnaming desktop ejws ; \
	do ln -sf $(SYS_JARDIRECTORY)/libbzdev-$$i.jar $(ECDBDIR) ; done
	if [ -f $(EXTLIBS1) ] ; then ln -sf $(EXTLIBS1) $(ECDBDIR) ; fi
	if [ -f $(EXTLIBS2) ] ; then ln -sf $(EXTLIBS2) $(ECDBDIR) ; fi
	if [ -f $(SYS_JARDIRECTORY)/derby.jar ] ; then \
		ln -sf $(SYS_JARDIRECTORY)/derby.jar $(ECDBDIR);
	for i in ecdb ecdb-dryrun do ; \
		if [ -h $(JARDIRECTORY)/$$i.jar ] ; \
		then rm -f $(JARDIRECTORY)/$$i.jar ; fi ; \
		ln -s $(JARDIRECTORY)/$$i-$(VERSION).jar \
			$(JARDIRECTORY)/$$i.jar ; \
		if [ -h $(ECDBDIR)/$$i.jar ] ; then rm $(ECDBDIR)/$$i.jar; fi; \
		ln -s $(JARDIRECTORY)/$$i.jar $(ECDBDIR)/$$i.jar
	done

install-doc: $(JROOT_JAVADOCS)/index.html
	install -d $(API_DOCDIR)
	install -d $(JAVADOCS)
	for i in `cd $(JROOT_JAVADOCS); find . -type d -print ` ; \
		do install -d $(JAVADOCS)/$$i ; done
	for i in `cd $(JROOT_JAVADOCS); find . -type f -print ` ; \
		do j=`dirname $$i`; install -m 0644 $(JROOT_JAVADOCS)/$$i \
			$(JAVADOCS)/$$j ; \
		done

install-desktop: all
	install -d $(BIN)
	install -d $(MANDIR)
	install -d $(MANDIR)/man1
	install -d $(MANDIR)/man5
	install -m 0755 $(JROOT_BIN)/ecdb $(BIN)
	install -m 0644 $(JROOT_MANDIR)/man1/ecdb.1.gz $(MANDIR)/man1
	install -m 0644 $(JROOT_MANDIR)/man5/ecdb.5.gz $(MANDIR)/man5
	install -d $(APP_ICON_DIR)
	install -d $(MIME_ICON_DIR)
	install -d $(MIMEDIR)
	install -d $(MIMEDIR)/packages
	install -d $(APPDIR)
	install -m 0644 -T $(SOURCEICON) $(APP_ICON_DIR)/$(TARGETICON)
	for i in $(ICON_WIDTHS) ; do \
		install -d $(ICON_DIR)/$${i}x$${i}/$(APPS_DIR) ; \
		inkscape -w $$i --export-filename=tmp.png $(SOURCEICON) ; \
		install -m 0644 -T tmp.png \
			$(ICON_DIR)/$${i}x$${i}/$(APPS_DIR)/$(TARGETICON_PNG); \
		rm tmp.png ; \
	done
	for i in $(ICON_WIDTHS2x) 512 ; do \
		ii=`expr 2 '*' $$i` ; \
		install -d $(ICON_DIR)/$${i}x$${i}@2x/$(APPS_DIR) ; \
		inkscape -w $$ii --export-filename=tmp.png $(SOURCEICON) ; \
		install -m 0644 -T tmp.png \
		    $(ICON_DIR)/$${i}x$${i}@2x/$(APPS_DIR)/$(TARGETICON_PNG); \
		rm tmp.png ; \
	done
	install -m 0644 -T mime/ecdb.xml $(MIMEDIR)/packages/ecdb.xml
	install -m 0644 -T $(SOURCE_FILE_ICON) \
		$(MIME_ICON_DIR)/$(TARGET_FILE_ICON)
	for i in $(ICON_WIDTHS) ; do \
	    install -d $(ICON_DIR)/$${i}x$${i}/$(MIMETYPES_DIR) ; \
	done;
	for i in $(ICON_WIDTHS) ; do \
	  inkscape -w $$i --export-filename=tmp.png $(SOURCE_FILE_ICON) ; \
	  install -m 0644 -T tmp.png \
	  $(ICON_DIR)/$${i}x$${i}/$(MIMETYPES_DIR)/$(TARGET_FILE_ICON_PNG); \
	  rm tmp.png ; \
	done
	for i in $(ICON_WIDTHS2x) ; do \
	    install -d $(ICON_DIR)/$${i}x$${i}@2x/$(MIMETYPES_DIR) ; \
	done;
	for i in $(ICON_WIDTHS2x) ; do \
	  ii=`expr 2 '*' $$i` ; \
	  inkscape -w $$ii --export-filename=tmp.png $(SOURCE_FILE_ICON) ; \
	  install -m 0644 -T tmp.png \
	  $(ICON_DIR)/$${i}x$${i}@2x/$(MIMETYPES_DIR)/$(TARGET_FILE_ICON_PNG); \
	  rm tmp.png ; \
	done
	install -m 0644 ecdb.desktop $(APPDIR)

install-pop:
	install -d $(APP_POPICON_DIR)
	install -d $(MIME_POPICON_DIR)
	install -m 0644 -T $(SOURCE_FILE_ICON) \
		$(MIME_POPICON_DIR)/$(TARGET_FILE_ICON)
	install -m 0644 -T $(SOURCEICON) $(APP_POPICON_DIR)/$(TARGETICON)
	for i in $(POPICON_WIDTHS) ; do \
		install -d $(POPICON_DIR)/$${i}x$${i}/$(APPS_DIR) ; \
		inkscape -w $$i --export-filename=tmp.png $(SOURCEICON) ; \
		install -m 0644 -T tmp.png \
		  $(POPICON_DIR)/$${i}x$${i}/$(APPS_DIR)/$(TARGETICON_PNG); \
		rm tmp.png ; \
	done
	for i in $(POPICON_WIDTHS2x) ; do \
		ii=`expr 2 '*' $$i` ; \
		install -d $(POPICON_DIR)/$${i}x$${i}@2x/$(APPS_DIR) ; \
		inkscape -w $$ii --export-filename=tmp.png $(SOURCEICON) ; \
		install -m 0644 -T tmp.png \
		  $(POPICON_DIR)/$${i}x$${i}@2x/$(APPS_DIR)/$(TARGETICON_PNG); \
		rm tmp.png ; \
	done

	for i in $(POPICON_WIDTHS) ; do \
	    install -d $(POPICON_DIR)/$${i}x$${i}/$(MIMETYPES_DIR) ; \
	done;
	for i in $(POPICON_WIDTHS) ; do \
	  inkscape -w $$i --export-filename=tmp.png $(SOURCE_FILE_ICON) ; \
	  install -m 0644 -T tmp.png \
	  $(POPICON_DIR)/$${i}x$${i}/$(MIMETYPES_DIR)/$(TARGET_FILE_ICON_PNG); \
	  rm tmp.png ; \
	done;
	for i in $(POPICON_WIDTHS2x) ; do \
	    install -d $(POPICON_DIR)/$${i}x$${i}@2x/$(MIMETYPES_DIR) ; \
	done;
	for i in $(POPICON_WIDTHS2x) ; do \
	  ii=`expr 2 '*' $$i`; \
	  inkscape -w $$ii --export-filename=tmp.png $(SOURCE_FILE_ICON) ; \
	  install -m 0644 -T tmp.png \
	$(POPICON_DIR)/$${i}x$${i}@2x/$(MIMETYPES_DIR)/$(TARGET_FILE_ICON_PNG);\
	  rm tmp.png ; \
	done ;

uninstall:
	@rm $(MANDIR)/man1/ecdb.1.gz || echo ... rm ecdb.1.gz  FAILED
	@rm $(MANDIR)/man5/ecdb.5.gz || echo ... rm ecdb.5.gz  FAILED
	@rm $(BIN)/ecdb   || echo ... rm $(BIN)/ecdb FAILED
	@rm $(JARDIRECTORY)/ecdb-$(VERSION).jar \
		|| echo ... rm ecdb-$(VERSION).jar FAILED

uninstall-links:
	for i in base obnaming desktop ejws ; \
	do rm -f $(ECDBDIR)/libbzdev-$$i.jar $(ECDBDIR) ; done
	for i in $(EXTJARS) ; do rm -f $(ECDBDIR)/$$i ; done

uninstall-doc:
	@rm -rf $(API_DOCDIR)

uninstall-desktop:
	@rm $(APPDIR)/ecdb.desktop || echo ... rm ecdb.desktop FAILED
	@for i in $(ICON_WIDTHS) ; do \
	   rm $(ICON_DIR)/$${i}x$${i}/$(APPS_DIR)/$(TARGETICON_PNG) \
		|| echo .. rm $(TARGETICON_PNG) from $${i}x$${i} FAILED; \
	done
	@rm $(APP_ICON_DIR)/$(TARGETICON)  || echo ... rm $(TARGETICON) FAILED
	@rm $(MIME_ICON_DIR)/$(TARGET_FILE_ICON)  || \
		echo ... rm $(TARGET_FILE_ICON) FAILED
	@for i in $(ICON_WIDTHS) ; do \
	  rm $(ICON_DIR)/$${i}x$${i}/$(MIMETYPES_DIR)/$(TARGET_FILE_ICON_PNG) \
		|| echo rm $(TARGET_FILE_ICON_PNG) from $${i}x$${i} FAILED; \
	done
	@(cd $(MIMEDIR)/packages ; \
	 rm ecdb.xml || echo rm .../ecdb.xml FAILED)
