/*
 * Copyright © 2018 Dennis Schulmeister-Zimolong
 *
 * E-Mail: dhbw@windows3.de
 * Webseite: https://www.wpvs.de/
 *
 * Dieser Quellcode ist lizenziert unter einer
 * Creative Commons Namensnennung 4.0 International Lizenz.
 */
package dhbwka.wwi.vertsys.pubsub.fahrzeug;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Hauptklasse unseres kleinen Progrämmchens.
 *
 * Mit etwas Google-Maps-Erfahrung lassen sich relativ einfach eigene
 * Wegstrecken definieren. Man muss nur Rechtsklick auf einen Punkt machen und
 * "Was ist hier?" anklicken, um die Koordinaten zu sehen. Allerdings speichert
 * Goolge Maps eine Nachkommastelle mehr, als das ITN-Format erlaubt. :-)
 */
public class Main {

    public static void main(String[] args) throws Exception {

        // Fahrzeug-ID abfragen
        String vehicleId = Utils.askInput("Beliebige Fahrzeug-ID", "postauto");

        // Zu fahrende Strecke abfragen
        File workdir = new File("./waypoints");
        String[] waypointFiles = workdir.list((File dir, String name) -> {
            return name.toLowerCase().endsWith(".itn");
        });

        System.out.println();
        System.out.println("Aktuelles Verzeichnis: " + workdir.getCanonicalPath());
        System.out.println();
        System.out.println("Verfügbare Wegstrecken");
        System.out.println();

        for (int i = 0; i < waypointFiles.length; i++) {
            System.out.println("  [" + i + "] " + waypointFiles[i]);
        }

        System.out.println();
        int index = Integer.parseInt(Utils.askInput("Zu fahrende Strecke", "0"));



        // TODO: Methode parseItnFile() unten ausprogrammieren
        List<WGS84> waypoints = parseItnFile(new File(workdir, waypointFiles[index]));

        // Adresse des MQTT-Brokers abfragen
        String mqttAddress = Utils.askInput("MQTT-Broker", Utils.MQTT_BROKER_ADDRESS);

        // TODO: Sicherstellen, dass bei einem Verbindungsabbruch eine sog.
        // LastWill-Nachricht gesendet wird, die auf den Verbindungsabbruch
        // hinweist. Die Nachricht soll eine "StatusMessage" sein, bei der das
        // Feld "type" auf "StatusType.CONNECTION_LOST" gesetzt ist.
        //
        // Die Nachricht muss dem MqttConnectOptions-Objekt übergeben werden
        // und soll an das Topic Utils.MQTT_TOPIC_NAME gesendet werden.

        MqttConnectOptions connectOptions = new MqttConnectOptions();

        StatusMessage lastWill = new StatusMessage();
        lastWill.type = StatusType.CONNECTION_LOST;
        lastWill.vehicleId = vehicleId;
        lastWill.message = "Dies ist eine 'Letzter Wille' Nachricht";
        connectOptions.setWill(Utils.MQTT_TOPIC_NAME, lastWill.toJson(), 0, true);

        // TODO: Verbindung zum MQTT-Broker herstellen.

        MqttClient client = new MqttClient(mqttAddress, "clientId", new MemoryPersistence());

        connectOptions.setCleanSession(true);
        System.out.println("Verbindung mit folgendem Broker hergestellt: " + mqttAddress);
        client.connect(connectOptions);
        System.out.println("Verbunden");

        // TODO: Statusmeldung mit "type" = "StatusType.VEHICLE_READY" senden.
        // Die Nachricht soll soll an das Topic Utils.MQTT_TOPIC_NAME gesendet
        // werden.

        StatusMessage statusMessage = new StatusMessage();
        statusMessage.type = StatusType.VEHICLE_READY;
        statusMessage.vehicleId = vehicleId;
        statusMessage.message = "Fahrzeug anmelden";

        MqttMessage mqttMessage = new MqttMessage();
        mqttMessage.setQos(0);
        mqttMessage.setPayload(statusMessage.toJson());

        client.publish(Utils.MQTT_TOPIC_NAME, mqttMessage);
        System.out.println("Nachricht wurde veröffentlicht");

        // TODO: Thread starten, der jede Sekunde die aktuellen Sensorwerte
        // des Fahrzeugs ermittelt und verschickt. Die Sensordaten sollen
        // an das Topic Utils.MQTT_TOPIC_NAME + "/" + vehicleId gesendet werden.
        Vehicle vehicle = new Vehicle(vehicleId, waypoints);
        vehicle.startVehicle();

        java.util.Timer timer = new java.util.Timer();
        System.out.println("Sensordaten werden ermittelt und verschickt");
        timer.schedule(new java.util.TimerTask()
        {
            public void run() {
                try
                {
                    client.publish(Utils.MQTT_TOPIC_NAME + "/" + vehicleId, new MqttMessage(vehicle.getSensorData().toJson()));
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        }, 0,1000);

        // Warten, bis das Programm beendet werden soll
        Utils.fromKeyboard.readLine();

        vehicle.stopVehicle();
        timer.cancel();
        System.out.println("Sensordaten wurden ermittlet und verschickt");

        // TODO: Oben vorbereitete LastWill-Nachricht hier manuell versenden,
        // da sie bei einem regulären Verbindungsende nicht automatisch
        // verschickt wird.
        //
        // Anschließend die Verbindung trennen und den oben gestarteten Thread
        // beenden, falls es kein Daemon-Thread ist.

        client.publish(Utils.MQTT_TOPIC_NAME, new MqttMessage(lastWill.toJson()));
        client.disconnect();
        System.out.println("Verbindung beendet");
        System.exit(0);

        }

    /**
     * Öffnet die in "filename" übergebene ITN-Datei und extrahiert daraus die
     * Koordinaten für die Wegstrecke des Fahrzeugs. Das Dateiformat ist ganz
     * simpel:
     *
     * <pre>
     * 0845453|4902352|Point 1 |0|
     * 0848501|4900249|Point 2 |0|
     * 0849295|4899460|Point 3 |0|
     * 0849796|4897723|Point 4 |0|
     * </pre>
     *
     * Jede Zeile enthält einen Wegpunkt. Die Datenfelder einer Zeile werden
     * durch | getrennt. Das erste Feld ist die "Longitude", das zweite Feld die
     * "Latitude". Die Zahlen müssen durch 100_000.0 geteilt werden.
     *
     * @param file ITN-Datei
     * @return Liste mit Koordinaten
     * @throws java.io.IOException
     */
    public static List<WGS84> parseItnFile(File file) throws IOException {
        List<WGS84> waypoints = new ArrayList<>();

        // TODO: Übergebene Datei parsen und Liste "waypoints" damit füllen

        String [] values = {"empty"};
        int i = 0;
        try {
            Scanner input = new Scanner(file);
            while (input.hasNext()) {
                String nextLine = input.nextLine();
                values = nextLine.split("\\|");
                if(values.length < 2)
                    System.out.println("Falsches Format in Zeile: " + nextLine);
                waypoints.add(new WGS84(Double.parseDouble(values[1])/100000,Double.parseDouble(values[0])/100000));
            }
        } catch (NumberFormatException numberFormatException) {
            System.out.println("Die Variable ist kein Double: " + values[i]);
            numberFormatException.printStackTrace();
        }

        return waypoints;
    }

}