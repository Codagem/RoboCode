package omega;

import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.awt.geom.Point2D;
import java.util.*;

public class OmegaWorldDuel extends TeamRobot {

    static final int BINS = 71;
    static final int MID = BINS / 2;

    double[][][][] gunStats = new double[7][6][5][BINS];
    double[][][][] surfStats = new double[7][6][5][BINS];

    ArrayList<EnemyWave> enemyWaves = new ArrayList<>();
    ArrayList<MyWave> myWaves = new ArrayList<>();
    ArrayList<Snapshot> history = new ArrayList<>();

    Enemy enemy = new Enemy();

    int moveDir = 1;
    double scoreGF = 4;
    double scoreKNN = 3;
    double scoreLinear = 1.2;
    double scoreHeadOn = 0.7;

    public void run() {
        setBodyColor(Color.darkGray);
        setGunColor(Color.blue);
        setRadarColor(Color.cyan);
        setBulletColor(Color.white);
        setScanColor(Color.green);

        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);

        while (true) {
            updateWaves();
            waveSurfMove();
            setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
            execute();
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {

        if (isTeammate(e.getName())) {
            return;
        }

        double absBearing = getHeadingRadians() + e.getBearingRadians();
        double enemyX = getX() + Math.sin(absBearing) * e.getDistance();
        double enemyY = getY() + Math.cos(absBearing) * e.getDistance();

        double lateralVelocity = e.getVelocity() * Math.sin(e.getHeadingRadians() - absBearing);
        double advancingVelocity = -e.getVelocity() * Math.cos(e.getHeadingRadians() - absBearing);
        int direction = lateralVelocity >= 0 ? 1 : -1;

        double energyDrop = enemy.energy - e.getEnergy();

        if (enemy.name == null) {
            energyDrop = 0;
        }

        if (energyDrop > 0 && energyDrop <= 3.0) {
            EnemyWave wave = new EnemyWave();
            wave.fireTime = getTime() - 1;
            wave.bulletVelocity = bulletVelocity(energyDrop);
            wave.distanceTraveled = wave.bulletVelocity;
            wave.fireLocation = new Point2D.Double(enemyX, enemyY);
            wave.directAngle = absBearing + Math.PI;
            wave.direction = direction;
            wave.distanceIndex = distanceIndex(e.getDistance());
            wave.velocityIndex = velocityIndex(Math.abs(e.getVelocity()));
            wave.wallIndex = wallIndex(enemyX, enemyY);
            enemyWaves.add(wave);
        }

        enemy.name = e.getName();
        enemy.x = enemyX;
        enemy.y = enemyY;
        enemy.energy = e.getEnergy();
        enemy.distance = e.getDistance();
        enemy.heading = e.getHeadingRadians();
        enemy.velocity = e.getVelocity();
        enemy.absBearing = absBearing;
        enemy.lateralVelocity = lateralVelocity;
        enemy.advancingVelocity = advancingVelocity;
        enemy.direction = direction;

        addSnapshot(e, enemy);

        lockRadar(absBearing);
        eliteGun(e);
        waveSurfMove();
    }

    private void lockRadar(double absBearing) {
        double radarTurn = Utils.normalRelativeAngle(absBearing - getRadarHeadingRadians());
        setTurnRadarRightRadians(radarTurn * 2.8);
    }

    private void eliteGun(ScannedRobotEvent e) {
        double firePower = chooseFirePower(e.getDistance(), e.getEnergy());
        double bulletVelocity = bulletVelocity(firePower);

        int d = distanceIndex(e.getDistance());
        int v = velocityIndex(Math.abs(e.getVelocity()));
        int w = wallIndex(enemy.x, enemy.y);

        double gfAngle = guessFactorAngle(d, v, w, bulletVelocity);
        double knnAngle = knnAngle(bulletVelocity);
        double linearAngle = linearAngle(e, bulletVelocity);
        double headOnAngle = enemy.absBearing;

        double total = scoreGF + scoreKNN + scoreLinear + scoreHeadOn;

        double finalAngle = circularMean(
                new double[]{gfAngle, knnAngle, linearAngle, headOnAngle},
                new double[]{scoreGF / total, scoreKNN / total, scoreLinear / total, scoreHeadOn / total}
        );

        setTurnGunRightRadians(Utils.normalRelativeAngle(finalAngle - getGunHeadingRadians()));

        if (getGunHeat() == 0 &&
                Math.abs(getGunTurnRemainingRadians()) < Math.toRadians(4) &&
                getEnergy() > firePower + 0.4) {

            Bullet b = setFireBullet(firePower);

            if (b != null) {
                MyWave wave = new MyWave();
                wave.fireTime = getTime();
                wave.fireLocation = new Point2D.Double(getX(), getY());
                wave.bulletVelocity = bulletVelocity;
                wave.distanceTraveled = 0;
                wave.directAngle = enemy.absBearing;
                wave.direction = enemy.direction;
                wave.gfAngle = gfAngle;
                wave.knnAngle = knnAngle;
                wave.linearAngle = linearAngle;
                wave.headOnAngle = headOnAngle;
                wave.distanceIndex = d;
                wave.velocityIndex = v;
                wave.wallIndex = w;
                myWaves.add(wave);
            }
        }
    }

    private void waveSurfMove() {
        EnemyWave wave = closestSurfableWave();

        if (enemy.name == null) {
            setAhead(120 * moveDir);
            return;
        }

        if (wave == null) {
            double angle = enemy.absBearing + Math.PI / 2 * moveDir;
            angle = wallSmoothing(getX(), getY(), angle, moveDir);

            setTurnRightRadians(Utils.normalRelativeAngle(angle - getHeadingRadians()));
            setAhead(150 * moveDir);
            return;
        }

        double dangerLeft = surfDanger(wave, -1);
        double dangerRight = surfDanger(wave, 1);

        int direction = dangerLeft < dangerRight ? -1 : 1;
        moveDir = direction;

        double angle = absoluteBearing(wave.fireLocation, new Point2D.Double(getX(), getY()));
        angle += Math.PI / 2 * direction;
        angle = wallSmoothing(getX(), getY(), angle, direction);

        setTurnRightRadians(Utils.normalRelativeAngle(angle - getHeadingRadians()));
        setAhead(185 * direction);
    }

    private double surfDanger(EnemyWave wave, int direction) {
        Point2D.Double predicted = predictPosition(wave, direction);
        double offset = Utils.normalRelativeAngle(absoluteBearing(wave.fireLocation, predicted) - wave.directAngle);
        double gf = limit(-1, offset / maxEscapeAngle(wave.bulletVelocity), 1) * wave.direction;
        int index = gfIndex(gf);

        return surfStats[wave.distanceIndex][wave.velocityIndex][wave.wallIndex][index] + 0.04;
    }

    private Point2D.Double predictPosition(EnemyWave wave, int direction) {
        Point2D.Double predicted = new Point2D.Double(getX(), getY());
        double velocity = getVelocity();
        double heading = getHeadingRadians();

        for (int i = 0; i < 100; i++) {
            double moveAngle = absoluteBearing(wave.fireLocation, predicted) + Math.PI / 2 * direction;
            moveAngle = wallSmoothing(predicted.x, predicted.y, moveAngle, direction);

            double turn = Utils.normalRelativeAngle(moveAngle - heading);
            double maxTurn = Math.PI / 720d * (40d - 3d * Math.abs(velocity));
            turn = limit(-maxTurn, turn, maxTurn);

            heading = Utils.normalRelativeAngle(heading + turn);
            velocity = limit(-8, velocity + direction, 8);

            predicted.x += Math.sin(heading) * velocity;
            predicted.y += Math.cos(heading) * velocity;

            predicted.x = limit(18, predicted.x, getBattleFieldWidth() - 18);
            predicted.y = limit(18, predicted.y, getBattleFieldHeight() - 18);

            if (wave.distanceTraveled + i * wave.bulletVelocity > wave.fireLocation.distance(predicted) - 18) {
                break;
            }
        }

        return predicted;
    }

    private void updateWaves() {
        Point2D.Double myPos = new Point2D.Double(getX(), getY());

        for (int i = enemyWaves.size() - 1; i >= 0; i--) {
            EnemyWave wave = enemyWaves.get(i);
            wave.distanceTraveled = (getTime() - wave.fireTime) * wave.bulletVelocity;

            if (wave.distanceTraveled > wave.fireLocation.distance(myPos) + 70) {
                enemyWaves.remove(i);
            }
        }

        if (enemy.name == null) {
            return;
        }

        Point2D.Double enemyPos = new Point2D.Double(enemy.x, enemy.y);

        for (int i = myWaves.size() - 1; i >= 0; i--) {
            MyWave wave = myWaves.get(i);
            wave.distanceTraveled = (getTime() - wave.fireTime) * wave.bulletVelocity;

            if (wave.distanceTraveled > wave.fireLocation.distance(enemyPos) - 20) {
                double realAngle = absoluteBearing(wave.fireLocation, enemyPos);
                trainGun(wave, realAngle);
                trainVirtualGuns(wave, realAngle);
                myWaves.remove(i);
            }
        }
    }

    private void trainGun(MyWave wave, double realAngle) {
        double offset = Utils.normalRelativeAngle(realAngle - wave.directAngle);
        double gf = limit(-1, offset / maxEscapeAngle(wave.bulletVelocity), 1) * wave.direction;
        int index = gfIndex(gf);

        double[] segment = gunStats[wave.distanceIndex][wave.velocityIndex][wave.wallIndex];

        for (int i = 0; i < BINS; i++) {
            segment[i] += 1.0 / (Math.pow(index - i, 2) + 1);
        }
    }

    private void trainVirtualGuns(MyWave wave, double realAngle) {
        double gfError = Math.abs(Utils.normalRelativeAngle(wave.gfAngle - realAngle));
        double knnError = Math.abs(Utils.normalRelativeAngle(wave.knnAngle - realAngle));
        double linearError = Math.abs(Utils.normalRelativeAngle(wave.linearAngle - realAngle));
        double headError = Math.abs(Utils.normalRelativeAngle(wave.headOnAngle - realAngle));

        double hitWindow = Math.atan(18 / Math.max(80, wave.distanceTraveled));

        if (gfError < hitWindow) scoreGF += 0.45;
        else scoreGF *= 0.997;

        if (knnError < hitWindow) scoreKNN += 0.45;
        else scoreKNN *= 0.997;

        if (linearError < hitWindow) scoreLinear += 0.25;
        else scoreLinear *= 0.997;

        if (headError < hitWindow) scoreHeadOn += 0.15;
        else scoreHeadOn *= 0.997;

        scoreGF = limit(0.5, scoreGF, 12);
        scoreKNN = limit(0.5, scoreKNN, 12);
        scoreLinear = limit(0.2, scoreLinear, 7);
        scoreHeadOn = limit(0.1, scoreHeadOn, 4);
    }

    public void onHitByBullet(HitByBulletEvent e) {
        EnemyWave wave = closestSurfableWave();

        if (wave != null) {
            double hitAngle = getHeadingRadians() + e.getBearingRadians();
            double offset = Utils.normalRelativeAngle(hitAngle - wave.directAngle);
            double gf = limit(-1, offset / maxEscapeAngle(wave.bulletVelocity), 1) * wave.direction;
            int index = gfIndex(gf);

            double[] segment = surfStats[wave.distanceIndex][wave.velocityIndex][wave.wallIndex];

            for (int i = 0; i < BINS; i++) {
                segment[i] += 1.0 / (Math.pow(index - i, 2) + 1);
            }
        }

        moveDir *= -1;
        setAhead(190 * moveDir);
    }

    public void onHitWall(HitWallEvent e) {
        moveDir *= -1;
        setBack(150);
        setTurnRight(90);
    }

    public void onHitRobot(HitRobotEvent e) {

        if (isTeammate(e.getName())) {
            setBack(120);
            setTurnRight(90);
            return;
        }

        setBack(120);
        setFire(3);
    }

    private EnemyWave closestSurfableWave() {
        EnemyWave best = null;
        double closest = Double.POSITIVE_INFINITY;
        Point2D.Double myPos = new Point2D.Double(getX(), getY());

        for (EnemyWave wave : enemyWaves) {
            double distance = wave.fireLocation.distance(myPos) - wave.distanceTraveled;

            if (distance > wave.bulletVelocity && distance < closest) {
                closest = distance;
                best = wave;
            }
        }

        return best;
    }

    private double guessFactorAngle(int d, int v, int w, double bulletVelocity) {
        double[] segment = gunStats[d][v][w];
        int best = MID;

        for (int i = 0; i < BINS; i++) {
            if (segment[i] > segment[best]) {
                best = i;
            }
        }

        double gf = (double)(best - MID) / MID;
        return enemy.absBearing + enemy.direction * gf * maxEscapeAngle(bulletVelocity);
    }

    private double knnAngle(double bulletVelocity) {
        if (history.size() < 15) {
            return enemy.absBearing;
        }

        ArrayList<Neighbor> neighbors = new ArrayList<>();

        for (Snapshot s : history) {
            double dist =
                    square((enemy.distance - s.distance) / 800.0) +
                    square((Math.abs(enemy.velocity) - Math.abs(s.velocity)) / 8.0) +
                    square((enemy.lateralVelocity - s.lateralVelocity) / 8.0) +
                    square((enemy.advancingVelocity - s.advancingVelocity) / 8.0) +
                    square((wallIndex(enemy.x, enemy.y) - s.wallIndex) / 5.0);

            neighbors.add(new Neighbor(dist, s.guessFactor));
        }

        Collections.sort(neighbors);

        int k = Math.min(24, neighbors.size());
        double weighted = 0;
        double weightSum = 0;

        for (int i = 0; i < k; i++) {
            Neighbor n = neighbors.get(i);
            double weight = 1.0 / (0.03 + n.distance);
            weighted += n.guessFactor * weight;
            weightSum += weight;
        }

        double gf = weightSum == 0 ? 0 : weighted / weightSum;
        gf = limit(-1, gf, 1);

        return enemy.absBearing + enemy.direction * gf * maxEscapeAngle(bulletVelocity);
    }

    private double linearAngle(ScannedRobotEvent e, double bulletVelocity) {
        double px = enemy.x;
        double py = enemy.y;

        for (int t = 0; t < 100; t++) {
            px += Math.sin(e.getHeadingRadians()) * e.getVelocity();
            py += Math.cos(e.getHeadingRadians()) * e.getVelocity();

            px = limit(18, px, getBattleFieldWidth() - 18);
            py = limit(18, py, getBattleFieldHeight() - 18);

            if (Point2D.distance(getX(), getY(), px, py) / bulletVelocity <= t) {
                break;
            }
        }

        return Math.atan2(px - getX(), py - getY());
    }

    private void addSnapshot(ScannedRobotEvent e, Enemy en) {
        Snapshot s = new Snapshot();

        s.distance = e.getDistance();
        s.velocity = e.getVelocity();
        s.lateralVelocity = en.lateralVelocity;
        s.advancingVelocity = en.advancingVelocity;
        s.wallIndex = wallIndex(en.x, en.y);
        s.guessFactor = limit(-1, en.lateralVelocity / 8.0, 1);

        history.add(s);

        if (history.size() > 3500) {
            history.remove(0);
        }
    }

    private double chooseFirePower(double distance, double enemyEnergy) {
        double power;

        if (distance < 150) power = 3.0;
        else if (distance < 320) power = 2.45;
        else if (distance < 520) power = 1.85;
        else power = 1.35;

        if (getEnergy() < 20) power = Math.min(power, 1.1);
        if (enemyEnergy < 14) power = Math.min(power, 1.6);

        return limit(0.8, power, 3.0);
    }

    private double wallSmoothing(double x, double y, double angle, int orientation) {
        double stick = 160;

        for (int i = 0; i < 170; i++) {
            double testX = x + Math.sin(angle) * stick;
            double testY = y + Math.cos(angle) * stick;

            if (testX > 30 && testY > 30 &&
                    testX < getBattleFieldWidth() - 30 &&
                    testY < getBattleFieldHeight() - 30) {
                break;
            }

            angle += orientation * 0.045;
        }

        return angle;
    }

    private int gfIndex(double gf) {
        return (int) limit(0, Math.round((gf + 1) * 0.5 * (BINS - 1)), BINS - 1);
    }

    private int distanceIndex(double d) {
        return (int) limit(0, d / 120, 6);
    }

    private int velocityIndex(double v) {
        return (int) limit(0, v / 1.7, 5);
    }

    private int wallIndex(double x, double y) {
        double wall = Math.min(
                Math.min(x, y),
                Math.min(getBattleFieldWidth() - x, getBattleFieldHeight() - y)
        );

        return (int) limit(0, wall / 80, 4);
    }

    private double bulletVelocity(double power) {
        return 20 - 3 * power;
    }

    private double maxEscapeAngle(double bulletVelocity) {
        return Math.asin(8.0 / bulletVelocity);
    }

    private double absoluteBearing(Point2D.Double source, Point2D.Double target) {
        return Math.atan2(target.x - source.x, target.y - source.y);
    }

    private double circularMean(double[] angles, double[] weights) {
        double x = 0;
        double y = 0;

        for (int i = 0; i < angles.length; i++) {
            x += Math.sin(angles[i]) * weights[i];
            y += Math.cos(angles[i]) * weights[i];
        }

        return Math.atan2(x, y);
    }

    private double square(double x) {
        return x * x;
    }

    private double limit(double min, double value, double max) {
        return Math.max(min, Math.min(value, max));
    }

    static class Enemy {
        String name;
        double x, y, energy, distance, heading, velocity;
        double absBearing, lateralVelocity, advancingVelocity;
        int direction = 1;
    }

    static class EnemyWave {
        Point2D.Double fireLocation;
        long fireTime;
        double bulletVelocity;
        double distanceTraveled;
        double directAngle;
        int direction;
        int distanceIndex, velocityIndex, wallIndex;
    }

    static class MyWave {
        Point2D.Double fireLocation;
        long fireTime;
        double bulletVelocity;
        double distanceTraveled;
        double directAngle;
        int direction;
        double gfAngle, knnAngle, linearAngle, headOnAngle;
        int distanceIndex, velocityIndex, wallIndex;
    }

    static class Snapshot {
        double distance, velocity, lateralVelocity, advancingVelocity;
        double guessFactor;
        int wallIndex;
    }

    static class Neighbor implements Comparable<Neighbor> {
        double distance;
        double guessFactor;

        Neighbor(double distance, double guessFactor) {
            this.distance = distance;
            this.guessFactor = guessFactor;
        }

        public int compareTo(Neighbor other) {
            return Double.compare(this.distance, other.distance);
        }
    }
}