Android Auto Messaging API Sample
=================================

MessagingService.java shows a simple service that sends notifications using NotificationCompat.
In addition to sending a notification, it also extends the notification with a CarExtender.
Each unread conversation from a user is sent as a distinct notification.

CheckList while building a messaging app that supports Android Auto:
-------------------------------------------------------------------
1. Add or import the android-auto-sdk.jar into your app.
2. Ensure that Message notifications are extended using
NotificationCompat.Builder.extend(new CarExtender()...)
3. Add meta-data to your AndroidManifest.xml to specify that your app
is automotive enabled.

       <meta-data android:name="com.google.android.gms.car.application"
                   android:resource="@xml/automotive_app_desc"/>

and include the following to indicate that the application wants to show notifications on
the overview screen.
res/xml/automotive_app_desc.xml

<automotiveApp>
    <uses name="notification"/>
</automotiveApp>

Flow
-----
MessagingFragment is shown to the user. Depending on the button clicked, the MessagingService is
sent a message. MessagingService inturn creates notifications which can be viewed either on the
emulator or in a car.
When a message is read, the associated PendingIntent is called and MessageReadReceiver is called
with the appropriate conversationId. Similarly, when a reply is received, the MessageReplyReceiver
is called with the appropriate conversationId. MessageLogger logs each event and shows them in a
TextView in MessagingFragment for correlation.

Known Issues:
-------------
- Emulator: Reply always sends text "This is a reply". No voice input in emulator.
