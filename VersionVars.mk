BUILD = $(shell \
	x="`git merge-base HEAD HEAD`" ;\
	y="`git merge-base HEAD master`" ;\
	if [ "$$x" = "$$y" ] ;\
	then \
		major="`git show HEAD:MAJOR 2>/dev/null || echo 0`";\
		minor="`git show HEAD:MINOR 2>/dev/null || echo 0`";\
		b=0 ;\
		if git diff --quiet ; then ind=1; else ind=0 ; fi ;\
		while prevmajor="`git show HEAD~$$ind:MAJOR 2>/dev/null`" && \
		      prevminor="`git show HEAD~$$ind:MINOR 2>/dev/null`" && \
		      [ "$$major" = "$$prevmajor" ] && \
		      [ "$$minor" = "$$prevminor" ] ;\
		do b=`expr $$b + 1` ;\
		   ind=`expr $$ind + 1` ;\
		done ;\
		echo ".$$b" ;\
	else echo "" ;\
	fi ; )

PKG_BUILD = $(shell \
	x="`git merge-base HEAD HEAD`" ;\
	y="`git merge-base HEAD master`" ;\
	z="" ;\
	if [ "$$x" = "$$y" ] ;\
	then \
		echo -n ;\
	else \
		for tag in `git tag -l 'v*'` ;\
		do \
			y="`git merge-base HEAD $$tag 2>/dev/null || echo -n`" ;\
			if [ "$$x" = "$$y" ] ;\
			then \
				z="`echo '$$tag' | sed -e s/^v// \
					-e 's/^[^-]*//' -e s/^-// | \
					grep -v '[^.~+A-Za-z0-9]'`" ;\
				break ;\
			fi ;\
		done ;\
		if [ -n "$$z" ] ;\
		then \
			echo "$$z" ;\
		else \
			for b in `git branch | sed -e 's/^[*][ 	]*//'` ;\
			do \
				y="`git merge-base HEAD $$b`" ;\
				if [ "$$x" = "$$y" ] ;\
				then \
					z="`echo $$b | sed -e 's/[-]/~/g' \
						-e 's/[_]/./g' \
						-e 's/^[0123456789]/No.\\0/' \
						-e 's/[^+.~a-zA-Z0-9]/+/g'`" ;\
					break ;\
				fi ;\
			done ;\
			major="`git show HEAD:MAJOR 2>/dev/null || echo 0`";\
			minor="`git show HEAD:MINOR 2>/dev/null || echo 0`";\
			b=0 ;\
			if git diff --quiet ; then ind=1; else ind=0 ; fi ;\
			while \
			  prevmajor="`git show HEAD~$$ind:MAJOR 2>/dev/null`" \
			&& \
			  prevminor="`git show HEAD~$$ind:MINOR 2>/dev/null`" \
			&& \
			  [ "$$major" = "$$prevmajor" ] \
			&& \
			  [ "$$minor" = "$$prevminor" ] ;\
			do \
				b=`expr $$b + 1` ;\
				ind=`expr $$ind + 1` ;\
			done ;\
			echo "-$$b$$z" ;\
		fi ;\
	fi ; )


# bzdevjlib version number.  Used to name JAR files.  The files MAJOR
# and MINOR contains the major and minor version numbers, and BUILD is
# set by counting parent commits until the major or minor version
# numbers change (starting from the current commit). Then VERSION is
# set to MAJOR.MINOR.BUILD
#
VERSION = $(shell echo `cat $(JROOT)/MAJOR`.`cat $(JROOT)/MINOR`)$(BUILD)

#
# The PKG_VERSION is the VERSION with an additional string for commits
# that are not along the Git master branch.  The intention is to allow
# one to create test/debug versions of the libary using Git branches.
#
PKG_VERSION = $(VERSION)$(PKG_BUILD)
