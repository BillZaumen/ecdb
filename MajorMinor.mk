buildno:
	@echo $(BUILD)

pkg_buildno:
	@echo $(PKG_BUILD)

version:
	@echo $(VERSION)

release:
	@cd $(JROOT) ;\
	if git diff --quiet ;\
	then \
		branch=`git branch|grep '*'|sed -e 's/[*][ 	][ 	]*//'`;\
		git tag -a v$(PKG_VERSION) -m "version $(PKG_VERSION)" ;\
		git push origin $$branch v$(PKG_VERSION) ;\
	else \
		echo modified files - cannot safely tag release ;\
		exit 1 ;\
	fi


minor:
	@cd $(JROOT) ;\
	x="`git log -1 --pretty=oneline HEAD`";\
	y="`git log -1 --pretty=oneline master`";\
	git diff --quiet HEAD -- MINOR ; \
	if [ $$? != 0 ]  ;\
	then \
		echo cannot update minor number - already changed ;\
	elif [ "$$x" = "$$y" ] ;\
	then \
		x="`git show master:MAJOR`" ;\
		y="`cat MAJOR`" ;\
		if [ "$$x" = "$$y" ] ;\
		then \
			m="$$(echo `git show master:MINOR`)";\
			m=`expr $$m + 1 `;\
			echo $$m > $(JROOT)/MINOR ;\
		fi ;\
	else \
		echo HEAD needs to point to the same commit as master \
		exit 1; \
	fi;

major:
	@cd $(JROOT) ;\
	x="`git log -1 --pretty=oneline HEAD`";\
	y="`git log -1 --pretty=oneline master`";\
	git diff --quiet HEAD -- MAJOR ; \
	if [ $$? != 0 ] ;\
	then \
		echo cannot update major number - already changed ;\
	if [ "$$x" = "$$y" ] ;\
	then \
		m="$$(echo `git show master:MAJOR`)";\
		m=`expr $$m + 1 `;\
		echo $$m > MAJOR ;\
		echo 0 > MINOR ;\
	else \
		echo HEAD needs to point to the same commit as master \
		exit 1; \
	fi;
