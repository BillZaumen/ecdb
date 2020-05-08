package org.bzdev.icalgen;
import java.util.*;

public class ICalAppointmentS {
    public static class Entry {
	Date startTime;
	Date endTime;
	String summary = null;
	String description;
	String location;
	int[] alarmOffsets;

	public Entry(Date startTime, Date endTime,
		     String summary, String description,
		     String location, int[] alarmOffsets)
	{
	    this.startTime = startTime;
	    this.endTime = endTime;
	    this.location = location;
	    if (alarmOffsets != null) {
		alarmOffsets = alarmOffsets.clone();
	    }
	    this.alarmOffsets = alarmOffsets;
	}
    }


}
