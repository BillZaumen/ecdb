<!DOCTYPE HTML>
<HTML lang="en">
<BODY>
<H2>Event times for $(+title:et)$(title) $(et)$(lastNameLast:elnl)$(+firstName:efn)$(firstName)$(+lastName:eln) $(eln)$(efn)$(lastName)$(elnl)$(lastNameFirst:elnf)$(+lastName:eln))$(lastName)$(+firstName:efn) $(efn)$(eln)$(firstName)$(elnf)</H2>
<P>$(singleOwner:soEnd)$(owners:endOwners)$(singleEvent:seEnd)
There is one event ($(summary)):
$(calendars:endCalendars)$(description) on $(startDate) at $(startTime)
(Location: $(location)).$(endCalendars)$(seEnd)$(multipleEvents:meEnd)
The events for $(summary) are
<UL>$(calendars:endCalendars)
    <LI> $(description) on $(startDate) at $(startTime)
         (Location: $(location))
$(endCalendars)</UL>$(meEnd)$(endOwners)$(soEnd)$(multipleOwners:moEnd)
The events are the following. For
<UL>$(owners:endOwners)
  <LI> $(summary):$(singleEvent:seEnd)$(calendars:endCals) $(description)
       on $(startDate) at $(startTime)
       (Location: $(location)).$(endCals)
$(seEnd)$(multipleEvents:meEnd)
     <UL>$(calendars:endCals)
        <LI> $(description) on $(startDate) at $(startTime)
	     (Location: $(location)).
$(endCals)</UL>$(meEnd)$(endOwners)$(moEnd)
</BODY>
</HTML>
