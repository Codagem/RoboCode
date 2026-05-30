package juninavancadov2;

import robocode.*;
import robocode.util.Utils;
import java.io.IOException;

public class JuninhoAdvanced extends TeamRobot {

    int moveDirection = 1;
    String alvoDoTime = null;

    public void run() {

        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);

        setBodyColor(java.awt.Color.BLACK);
        setGunColor(java.awt.Color.RED);
        setRadarColor(java.awt.Color.WHITE);
        setBulletColor(java.awt.Color.YELLOW);

        setTurnRadarRightRadians(Double.POSITIVE_INFINITY);

        while (true) {

            setAhead(150 * moveDirection);
            setTurnRight(30 * moveDirection);

            execute();
        }
    }

    public void onMessageReceived(MessageEvent e) {

        alvoDoTime = (String) e.getMessage();
    }

    public void onScannedRobot(ScannedRobotEvent e) {

        // Ignora companheiros
        if (isTeammate(e.getName())) {
            return;
        }

        // Compartilha alvo com a equipe
        try {
            broadcastMessage(e.getName());
        } catch (IOException ex) {
        }

        // Se recebeu alvo do líder, ignora outros inimigos
        if (alvoDoTime != null && !e.getName().equals(alvoDoTime)) {
            return;
        }

        double absoluteBearing =
                getHeadingRadians() + e.getBearingRadians();

        double gunTurn =
                Utils.normalRelativeAngle(
                        absoluteBearing - getGunHeadingRadians()
                );

        setTurnGunRightRadians(gunTurn);

        double radarTurn =
                Utils.normalRelativeAngle(
                        absoluteBearing - getRadarHeadingRadians()
                );

        setTurnRadarRightRadians(radarTurn * 2);

        double firePower;

        if (e.getDistance() < 100) {
            firePower = 3;
        } else if (e.getDistance() < 300) {
            firePower = 2;
        } else {
            firePower = 1;
        }

        if (getGunHeat() == 0 &&
                Math.abs(getGunTurnRemaining()) < 10) {

            fire(firePower);
        }

        setTurnRight(e.getBearing() + 90);

        if (e.getDistance() < 150) {
            setBack(100);
        } else {
            setAhead(100 * moveDirection);
        }

        execute();
    }

    public void onHitByBullet(HitByBulletEvent e) {

        moveDirection *= -1;

        setTurnRight(90 - e.getBearing());
        setAhead(150 * moveDirection);

        execute();
    }

    public void onHitWall(HitWallEvent e) {

        moveDirection *= -1;

        setBack(100);
        setTurnRight(60);

        execute();
    }

    public void onHitRobot(HitRobotEvent e) {

        // Não atira em aliado
        if (isTeammate(e.getName())) {

            setBack(120);
            setTurnRight(90);

            execute();
            return;
        }

        fire(3);

        setBack(80);

        execute();
    }
}