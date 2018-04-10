package utils;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.store.FileDataStoreFactory;

import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.*;
import com.google.api.services.calendar.model.Calendar;

import model.Activity;
import model.Activity.Frequency;
import model.SemesterInfo;
import model.Timetable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.security.InvalidParameterException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class TimetableBuilder {
    private static final String APPLICATION_NAME = "TimetableBuilder";
    private static final File DATA_STORE_DIR =
            new File(System.getProperty("user.home"), ".credentials/timetable-builder");
    private static FileDataStoreFactory DATA_STORE_FACTORY;
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static HttpTransport HTTP_TRANSPORT;
    private static com.google.api.services.calendar.Calendar service;

    /**
     * If modifying these scopes, delete your previously saved credentials
     * at ~/.credentials/timetable-builder
     */
    private static final List<String> SCOPES = Collections.singletonList(CalendarScopes.CALENDAR);

    static {
        try {
            HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
            DATA_STORE_FACTORY = new FileDataStoreFactory(DATA_STORE_DIR);
            service = getCalendarService();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static Credential authorize() throws IOException {
        InputStream in = TimetableBuilder.class.getResourceAsStream("/client.json");
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        GoogleAuthorizationCodeFlow flow =
                new GoogleAuthorizationCodeFlow.Builder(
                        HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
                        .setDataStoreFactory(DATA_STORE_FACTORY)
                        .setAccessType("online")
                        .build();
        return new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");
    }

    private static com.google.api.services.calendar.Calendar getCalendarService() throws IOException {
        Credential credential = authorize();
        return new com.google.api.services.calendar.Calendar.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    public static String createCalendar(Timetable timetable) throws IOException {
        Calendar newCalendar = new Calendar();

        String summary = timetable.getSemiGroup().equals("0") ?
                timetable.getGroup() + " Sem." + timetable.getSemester() :
                timetable.getGroup() + "/" + timetable.getSemiGroup() + " Sem." + timetable.getSemester();
        newCalendar.setSummary(summary);

        String description = "Timetable for group " + timetable.getGroup() + " for the semester " +
                timetable.getSemester() + "\n\n\tRed - Course\n\tGreen - Seminar\n\tYellow - Laboratory";
        newCalendar.setDescription(description);

        newCalendar.setTimeZone("Europe/Bucharest");

        return service.calendars().insert(newCalendar).execute().getId();
    }

    public static void addTimetable(String calendarId, Timetable timetable) throws IOException {
        List<Activity> allActivities = timetable.getAllActivities();
        for (Activity nextActivity : allActivities) {
            addActivity(calendarId, nextActivity, timetable.getSemester());
        }
    }

    private static void addActivity(String calendarId, Activity activity, int semester) throws IOException {
        Event event = new Event();

        setSummary(event, activity);
        setDescription(event, activity);
        setStartAndEndDate(event, activity, semester);
        setRecurrence(event, semester);
        setLocation(event, activity);
        setColor(event, activity);

        System.out.println("Generating event for " + event.getSummary());
        event = service.events().insert(calendarId, event).execute();

        deleteExtraEvents(calendarId, activity, event.getId(), semester);
    }

    private static void setSummary(Event event, Activity activity) {
        String title = "(" + activity.getType() + ") " + activity.getName();
        if (activity.getType() == Activity.Type.Laboratory && activity.getGroup().contains("/"))
            title += " " + activity.getGroup();
        event.setSummary(title);
    }

    private static void setDescription(Event event, Activity activity) {
        event.setDescription(activity.getType().name() + ' ' + activity.getName() + " - "
                + activity.getProfessor());
    }

    private static void setStartAndEndDate(Event event, Activity activity, int semester) {
        String startingDate = getStartingDateOfActivity(activity, semester).toString(),
                timeZone = (semester == 1 ? ":00.000+03:00" : ":00.000+02:00");

        DateTime start = DateTime.parseRfc3339(startingDate + "T" + activity.getStartTime() + timeZone);
        DateTime end = DateTime.parseRfc3339(startingDate + "T" + activity.getEndTime() + timeZone);

        event.setStart(new EventDateTime().setDateTime(start).setTimeZone("Europe/Bucharest"));
        event.setEnd(new EventDateTime().setDateTime(end).setTimeZone("Europe/Bucharest"));
    }

    private static LocalDate getStartingDateOfActivity(Activity activity, int semester) {
        String startingDate = SemesterInfo.getStartDate(semester);
        LocalDate localStartingDate = LocalDate.of(Integer.parseInt(startingDate.substring(0, 4)),
                Integer.parseInt(startingDate.substring(5, 7)),
                Integer.parseInt(startingDate.substring(8)));

        localStartingDate = localStartingDate.plus(activity.getDayOfWeek().index, ChronoUnit.DAYS);
        return localStartingDate;
    }

    private static void setRecurrence(Event event, int semester) {
        String recurrence = "RRULE:FREQ=WEEKLY;COUNT=" + SemesterInfo.getNoOfWeeks(semester);
        event.setRecurrence(Collections.singletonList(recurrence));
    }

    private static void setLocation(Event event, Activity activity) {
        event.setLocation(activity.getRoom());
    }

    private static void setColor(Event event, Activity activity) {
        final int colorId;

        switch (activity.getType()) {
            case Lecture:
                colorId = 4; // light red
                break;
            case Seminar:
                colorId = 10; // green
                break;
            case Laboratory:
                colorId = 5; // yellow
                break;
            default:
                throw new InvalidParameterException("No such activity type: " + activity.getType());
        }

        event.setColorId(Integer.toString(colorId));
    }

    private static void deleteExtraEvents(String calendarID, Activity activity, String eventID, int semester)
            throws IOException {
        String pageToken = null;
        do {
            Events events =
                    service.events().instances(calendarID, eventID).setPageToken(pageToken).execute();
            List<Event> items = events.getItems();
            deleteExtraEvents(calendarID, activity, items, semester);
            pageToken = events.getNextPageToken();
        } while (pageToken != null);
    }

    private static void deleteExtraEvents(String calendarID, Activity activity, List<Event> items, int semester)
            throws IOException {
        int holidayLength = SemesterInfo.getHolidayLength(semester);
        int holidayStartWeek = SemesterInfo.getHolidayStartWeek(semester);

        for (int week = 0; week < holidayLength; week++) {
            service.events().delete(calendarID, items.get(holidayStartWeek + week).getId()).execute();
        }

        Activity.Frequency frequency = activity.getFrequency();
        if (frequency == Activity.Frequency.Weekly) {
            return;
        }

        // --------------------------------

        for (int week = frequency.getSkipWeek(); week < holidayStartWeek; week += 2) {
            service.events().delete(calendarID, items.get(week).getId()).execute();
        }

        int nextWeekAfterHoliday = holidayStartWeek + holidayLength + frequency.getSkipWeek();
        for (int week = nextWeekAfterHoliday; week < SemesterInfo.getNoOfWeeks(semester); week += 2) {
            System.out.println("Deleting week " + week + " for frequency " + frequency);
            service.events().delete(calendarID, items.get(week).getId()).execute();
        }
    }

    public static void deleteCalendar(String calendarID) throws IOException {
        service.calendars().delete(calendarID).execute();
    }
}
