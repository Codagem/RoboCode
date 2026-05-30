package jr;

import robocode.*;
import java.io.IOException;

public class Juninho extends TeamRobot {

    private String alvoDoTime = null;

    public void run() {

        setColors(
                java.awt.Color.BLACK,
                java.awt.Color.RED,
                java.awt.Color.WHITE
        );

        while (true) {

            ahead(150);

            turnRight(90);

            turnGunRight(360);
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {

        // Ignora companheiros
        if (isTeammate(e.getName())) {
            return;
        }

        alvoDoTime = e.getName();

        try {
            broadcastMessage(alvoDoTime);
        } catch (IOException ex) {
        }

        fire(2);

        turnRight(30);
        ahead(50);
    }

    public void onMessageReceived(MessageEvent e) {

        alvoDoTime = (String) e.getMessage();
    }

    public void onHitByBullet(HitByBulletEvent e) {

        turnLeft(45);
        ahead(100);
    }

    public void onHitWall(HitWallEvent e) {

        back(80);

        turnRight(90);

        ahead(100);
    }

    public void onHitRobot(HitRobotEvent e) {

        if (isTeammate(e.getName())) {

            back(120);
            turnRight(90);
            return;
        }

        fire(3);

        back(50);
    }
}