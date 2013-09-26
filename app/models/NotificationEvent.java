package models;

import models.enumeration.EventType;
import models.enumeration.RequestState;
import models.enumeration.ResourceType;
import models.enumeration.State;
import models.resource.Resource;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import play.Configuration;
import play.db.ebean.Model;
import play.i18n.Messages;
import playRepository.Commit;

import javax.persistence.*;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Entity
public class NotificationEvent extends Model {
    private static final long serialVersionUID = 1L;

    private static final int NOTIFICATION_DRAFT_TIME_IN_MILLIS = Configuration.root()
            .getMilliseconds("application.notification.draft-time", 30 * 1000L).intValue();

    @Id
    public Long id;

    public static Finder<Long, NotificationEvent> find = new Finder<Long,
            NotificationEvent>(Long.class, NotificationEvent.class);

    public String title;

    @Lob
    public String message;

    public Long senderId;

    @ManyToMany(cascade = CascadeType.ALL)
    public Set<User> receivers;

    @Temporal(TemporalType.TIMESTAMP)
    public Date created;

    public String urlToView;

    @Enumerated(EnumType.STRING)
    public ResourceType resourceType;

    public String resourceId;

    @Enumerated(EnumType.STRING)
    public EventType eventType;

    @Lob
    public String oldValue;

    @Lob
    public String newValue;

    @OneToOne(mappedBy="notificationEvent", cascade = CascadeType.ALL)
    public NotificationMail notificationMail;

    public static String formatReplyTitle(Project project, Commit commit) {
        return String.format("Re: [%s] %s (%s)",
                project.name, commit.getShortMessage(), commit.getShortId());
    }

    public static String formatReplyTitle(PullRequest pullRequest) {
        return String.format("Re: [%s] %s (#%s)",
                pullRequest.toProject.name, pullRequest.title, pullRequest.id);
    }

    public static String formatReplyTitle(AbstractPosting posting) {
        return String.format("Re: [%s] %s (#%d)",
                posting.project.name, posting.title, posting.getNumber());
    }

    public static String formatReplyTitle(Project project, User user) {
        return String.format("Re: [%s] @%s wants to join your project", project.name, user.loginId);
    }

    public static String formatNewTitle(AbstractPosting posting) {
        return String.format("[%s] %s (#%d)",
                posting.project.name, posting.title, posting.getNumber());
    }

    public static String formatNewTitle(PullRequest pullRequest) {
        return String.format("[%s] %s (#%d)",
                pullRequest.toProject.name, pullRequest.title, pullRequest.id);
    }

    public static String formatNewTitle(Project project, User user) {
        return String.format("[%s] @%s wants to join your project", project.name, user.loginId);
    }

    public String getOldValue() {
        return oldValue;
    }

    @Transient
    public static Set<User> getMentionedUsers(String body) {
        Matcher matcher = Pattern.compile("@" + User.LOGIN_ID_PATTERN).matcher(body);
        Set<User> users = new HashSet<>();
        while(matcher.find()) {
            users.add(User.findByLoginId(matcher.group().substring(1)));
        }
        users.remove(User.anonymous);
        return users;
    }

    @Transient
    public String getMessage() {
        if (message != null) {
            return message;
        }

        switch (eventType) {
        case ISSUE_STATE_CHANGED:
            if (newValue.equals(State.CLOSED.state())) {
                return Messages.get("notification.issue.closed");
            } else {
                return Messages.get("notification.issue.reopened");
            }
        case ISSUE_ASSIGNEE_CHANGED:
            if (newValue == null) {
                return Messages.get("notification.issue.unassigned");
            } else {
                return Messages.get("notification.issue.assigned", newValue);
            }
        case NEW_ISSUE:
        case NEW_POSTING:
        case NEW_COMMENT:
        case NEW_PULL_REQUEST:
        case NEW_SIMPLE_COMMENT:
            return newValue;
        case PULL_REQUEST_STATE_CHANGED:
            if(newValue.equals(State.CLOSED.state())) {
                return Messages.get("notification.pullrequest.closed");
            } else if(newValue.equals(State.REJECTED.state())) {
                return Messages.get("notification.pullrequest.rejected");
            } else {
                return Messages.get("notification.pullrequest.reopened");
            }
        case PULL_REQUEST_COMMIT_CHANGED:
            return newValue;
        case PULL_REQUEST_MERGED:
            return Messages.get("notification.type.pull.request.merged." + newValue) + "\n" + StringUtils.defaultString(oldValue, StringUtils.EMPTY);
        case MEMBER_ENROLL_REQUEST:
            if (RequestState.REQUEST.name().equals(newValue)) {
                return Messages.get("notification.member.enroll.request");
            } else  if (RequestState.ACCEPT.name().equals(newValue)) {
                return Messages.get("notification.member.enroll.accept");
            } else {
                return Messages.get("notification.member.enroll.cancel");
            }
        default:
            return null;
        }
    }

    public User getSender() {
        return User.find.byId(this.senderId);
    }

    public Resource getResource() {
        return Resource.get(resourceType, resourceId);
    }

    public Project getProject() {
        switch(resourceType) {
        case ISSUE_ASSIGNEE:
            return Assignee.finder.byId(Long.valueOf(resourceId)).project;
        default:
            Resource resource = getResource();
            if (resource != null) {
                return resource.getProject();
            } else {
                return null;
            }
        }
    }

    public boolean resourceExists() {
        return Resource.exists(resourceType, resourceId);
    }

    public static void add(NotificationEvent event) {
        if (event.notificationMail == null) {
            event.notificationMail = new NotificationMail();
            event.notificationMail.notificationEvent = event;
        }

        Date draftDate = DateTime.now().minusMillis(NOTIFICATION_DRAFT_TIME_IN_MILLIS).toDate();

        NotificationEvent lastEvent = NotificationEvent.find.where()
                .eq("resourceId", event.resourceId)
                .eq("resourceType", event.resourceType)
                .gt("created", draftDate)
                .orderBy("id desc").setMaxRows(1).findUnique();

        if (lastEvent != null) {
            if (lastEvent.eventType == event.eventType &&
                    event.senderId.equals(lastEvent.senderId)) {
                // If the last event is A -> B and the current event is B -> C,
                // they are merged into the new event A -> C.
                event.oldValue = lastEvent.getOldValue();
                lastEvent.delete();

                // If the last event is A -> B and the current event is B -> A,
                // they are removed.
                if (StringUtils.equals(event.oldValue, event.newValue)) {
                    return;
                }
            }
        }

        // 특정 알림 유형에 대해 설정을 꺼둔 사용자가 있을 경우 수신인에서 제외
        Resource resource = Resource.get(event.resourceType, event.resourceId);
        Set<User> receivers = event.receivers;
        Set<User> filteredReceivers = new HashSet<>();
        for (User receiver : receivers) {
            if (UserProjectNotification.isEnabledNotiType(receiver, resource.getProject(), event.eventType)) {
                filteredReceivers.add(receiver);
            }
        }
        if (filteredReceivers.isEmpty()) {
            return;
        }

        event.receivers = filteredReceivers;
        event.save();
    }

    public static void deleteBy(Resource resource) {
        for (NotificationEvent event : NotificationEvent.find.where().where().eq("resourceType",
                resource.getType()).eq("resourceId", resource.getId()).findList()) {
            event.delete();
        }
    }

}
