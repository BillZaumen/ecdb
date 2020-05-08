Event times for $(+title:et)$(title) $(et)$(lastNameLast:elnl)$(+firstName:efn)$(firstName)$(+lastName:eln) $(eln)$(efn)$(lastName)$(elnl)$(lastNameFirst:elnf)$(+lastName:;eln))$(lastName)$(+firstName:efn) $(efn)$(eln)$(firstName)$(elnf)
$(singleOwner:soEnd)$(owners:endOwners)$(singleEvent:seEnd)
There is one event ($(summary)):
$(calendars:endCalendars)$(description) on $(startDate) at $(startTime)
(Location: $(location)).$(endCalendars)$(seEnd)$(multipleEvents:meEnd)
The events for $(summary) are $(calendars:endCalendars)

    * $(description) on $(startDate) at $(startTime)
      (Location: $(location))$(endCalendars)$(meEnd)$(endOwners)$(soEnd)$(multipleOwners:moEnd)
The events are the following. For$(owners:endOwners)

    * $(summary):$(singleEvent:seEnd)$(calendars:endCals) $(description)
      on $(startDate) at $(startTime)
      (Location: $(location)).$(endCals)$(seEnd)$(multipleEvents:meEnd)$(calendars:endCals)

        - $(description) on $(startDate) at $(startTime)
	  (Location: $(location)).$(endCals)$(meEnd)$(endOwners)$(moEnd)
