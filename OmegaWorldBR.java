import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.awt.geom.Point2D;
import java.util.*;

public class OmegaWorldBR extends TeamRobot {

    static final int BINS = 59;
    static final int MID = BINS / 2;

    double[][][][] gunStats = new double[6][5][5][BINS];
    double[][][][] surfStats = new double[6][5][5][BINS];

    ArrayList<EnemyWave> enemyWaves = new ArrayList<>();
    ArrayList<MyWave> myWaves = new ArrayList<>();
    ArrayList<Snapshot> history = new ArrayList<>();
    HashMap<String, Enemy> enemies = new HashMap<>();

    String target = null;
    int moveDir = 1;
    Random random = new Random();

    double scoreGF = 3;
    double scoreKNN = 2;
    double scoreLinear = 1.5;
    double scoreHeadOn = 1;

    public void run() {
        setBodyColor(Color.black);
        setGunColor(Color.red);
        setRadarColor(Color.orange);
        setBulletColor(Color.yellow);
        setScanColor(Color.white);

        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);

        while (true) {
            updateWaves();

            if (getOthers() > 2) {
                antiGravityMove();
            } else if (getOthers() == 1) {
                waveSurfMove();
            }

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

        Enemy en = enemies.get(e.getName());

        if (en == null) {
            en = new Enemy();
            en.energy = e.getEnergy();
            enemies.put(e.getName(), en);
        }

        double lateralVelocity = e.getVelocity() * Math.sin(e.getHeadingRadians() - absBearing);
        double advancingVelocity = -e.getVelocity() * Math.cos(e.getHeadingRadians() - absBearing);
        int direction = lateralVelocity >= 0 ? 1 : -1;

        double energyDrop = en.energy - e.getEnergy();

        en.name = e.getName();
        en.x = enemyX;
        en.y = enemyY;
        en.energy = e.getEnergy();
        en.distance = e.getDistance();
        en.heading = e.getHeadingRadians();
        en.velocity = e.getVelocity();
        en.lateralVelocity = lateralVelocity;
        en.advancingVelocity = advancingVelocity;
        en.absBearing = absBearing;
        en.direction = direction;
        en.lastSeen = getTime();

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

        addSnapshot(e, absBearing, en);

        chooseTarget(e, en);
        lockRadar(absBearing);

        if (e.getName().equals(target)) {
            eliteGun(e, en);
            eliteMove(e, en);
        }
    }

    private void chooseTarget(ScannedRobotEvent e, Enemy en) {
        if (target == null || !enemies.containsKey(target)) {
            target = e.getName();
            return;
        }

        Enemy current = enemies.get(target);

        if (current == null) {
            target = e.getName();
            return;
        }

        if (getOthers() > 2) {
            double newScore = en.distance + en.energy * 4;
            double oldScore = current.distance + current.energy * 4;

            if (newScore < oldScore || en.distance < 180) {
                target = e.getName();
            }
        } else {
            if (en.energy < current.energy || en.distance < 250) {
                target = e.getName();
            }
        }
    }

    private void lockRadar(double absBearing) {
        double radarTurn = Utils.normalRelativeAngle(absBearing - getRadarHeadingRadians());
        setTurnRadarRightRadians(radarTurn * 2.4);
    }

    private void eliteGun(ScannedRobotEvent e, Enemy en) {
        double firePower = chooseFirePower(e.getDistance(), e.getEnergy());
        double bulletVelocity = bulletVelocity(firePower);

        int d = distanceIndex(e.getDistance());
        int v = velocityIndex(Math.abs(e.getVelocity()));
        int w = wallIndex(en.x, en.y);

        double gfAngle = guessFactorAngle(en, d, v, w, bulletVelocity);
        double knnAngle = knnAngle(en, bulletVelocity);
        double linearAngle = linearAngle(e, en, bulletVelocity);
        double headOnAngle = en.absBearing;

        double total = scoreGF + scoreKNN + scoreLinear + scoreHeadOn;

        double finalAngle = circularMean(
                new double[]{gfAngle, knnAngle, linearAngle, headOnAngle},
                new double[]{scoreGF / total, scoreKNN / total, scoreLinear / total, scoreHeadOn / total}
        );

        setTurnGunRightRadians(Utils.normalRelativeAngle(finalAngle - getGunHeadingRadians()));

        if (getGunHeat() == 0 &&
                Math.abs(getGunTurnRemainingRadians()) < Math.toRadians(5) &&
                getEnergy() > firePower + 0.5) {

            Bullet b = setFireBullet(firePower);

            if (b != null) {
                MyWave wave = new MyWave();
                wave.fireTime = getTime();
                wave.fireLocation = new Point2D.Double(getX(), getY());
                wave.bulletVelocity = bulletVelocity;
                wave.directAngle = en.absBearing;
                wave.direction = en.direction;
                wave.targetName = en.name;
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

    private void eliteMove(ScannedRobotEvent e, Enemy en) {
        if (getOthers() > 2) {
            antiGravityMove();
            return;
        }

        if (getOthers() == 1 && !enemyWaves.isEmpty()) {
            waveSurfMove();
            return;
        }

        double angle = en.absBearing + Math.PI / 2 * moveDir;
        angle = wallSmoothing(getX(), getY(), angle, moveDir);

        setTurnRightRadians(Utils.normalRelativeAngle(angle - getHeadingRadians()));
        setAhead(150 * moveDir);

        if (random.nextInt(18) == 0) {
            moveDir *= -1;
        }
    }

    private void antiGravityMove() {
        double xForce = 0;
        double yForce = 0;

        for (Enemy en : enemies.values()) {
            if (getTime() - en.lastSeen > 35) continue;

            double dx = getX() - en.x;
            double dy = getY() - en.y;
            double distSq = Math.max(900, dx * dx + dy * dy);
            double force = 120000 / distSq;

            xForce += dx * force;
            yForce += dy * force;
        }

        double centerX = getBattleFieldWidth() / 2;
        double centerY = getBattleFieldHeight() / 2;

        xForce += (getX() - centerX) * 0.04;
        yForce += (getY() - centerY) * 0.04;

        xForce += 7000 / Math.pow(Math.max(70, getX()), 2);
        xForce -= 7000 / Math.pow(Math.max(70, getBattleFieldWidth() - getX()), 2);
        yForce += 7000 / Math.pow(Math.max(70, getY()), 2);
        yForce -= 7000 / Math.pow(Math.max(70, getBattleFieldHeight() - getY()), 2);

        double angle = Math.atan2(xForce, yForce);
        angle = wallSmoothing(getX(), getY(), angle, moveDir);

        setTurnRightRadians(Utils.normalRelativeAngle(angle - getHeadingRadians()));
        setAhead(160);
    }

    private void waveSurfMove() {
        EnemyWave wave = closestSurfableWave();

        if (wave == null) {
            setAhead(130 * moveDir);
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
        setAhead(170 * direction);
    }

    private double surfDanger(EnemyWave wave, int direction) {
        Point2D.Double predicted = predictPosition(wave, direction);
        double offset = Utils.normalRelativeAngle(absoluteBearing(wave.fireLocation, predicted) - wave.directAngle);
        double gf = limit(-1, offset / maxEscapeAngle(wave.bulletVelocity), 1) * wave.direction;
        int index = gfIndex(gf);

        return surfStats[wave.distanceIndex][wave.velocityIndex][wave.wallIndex][index] + 0.08;
    }

    private Point2D.Double predictPosition(EnemyWave wave, int direction) {
        Point2D.Double predicted = new Point2D.Double(getX(), getY());
        double velocity = getVelocity();
        double heading = getHeadingRadians();

        for (int i = 0; i < 90; i++) {
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

            if (wave.distanceTraveled + i * wave.bulletVelocity > wave.fireLocation.distance(predicted) - 20) {
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

            if (wave.distanceTraveled > wave.fireLocation.distance(myPos) + 80) {
                enemyWaves.remove(i);
            }
        }

        for (int i = myWaves.size() - 1; i >= 0; i--) {
            MyWave wave = myWaves.get(i);
            wave.distanceTraveled = (getTime() - wave.fireTime) * wave.bulletVelocity;

            Enemy en = enemies.get(wave.targetName);
            if (en == null) continue;

            Point2D.Double enemyPos = new Point2D.Double(en.x, en.y);

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

        if (gfError < hitWindow) scoreGF += 0.35;
        else scoreGF *= 0.998;

        if (knnError < hitWindow) scoreKNN += 0.35;
        else scoreKNN *= 0.998;

        if (linearError < hitWindow) scoreLinear += 0.25;
        else scoreLinear *= 0.998;

        if (headError < hitWindow) scoreHeadOn += 0.18;
        else scoreHeadOn *= 0.998;

        scoreGF = limit(0.5, scoreGF, 10);
        scoreKNN = limit(0.5, scoreKNN, 10);
        scoreLinear = limit(0.3, scoreLinear, 8);
        scoreHeadOn = limit(0.2, scoreHeadOn, 5);
    }

    public void onHitByBullet(HitByBulletEvent e) {
        EnemyWave wave = closestSurfableWave();

        if (wave != null) {
            double offset = Utils.normalRelativeAngle(e.getBearingRadians() + getHeadingRadians() - wave.directAngle);
            double gf = limit(-1, offset / maxEscapeAngle(wave.bulletVelocity), 1) * wave.direction;
            int index = gfIndex(gf);

            double[] segment = surfStats[wave.distanceIndex][wave.velocityIndex][wave.wallIndex];

            for (int i = 0; i < BINS; i++) {
                segment[i] += 1.0 / (Math.pow(index - i, 2) + 1);
            }
        }

        moveDir *= -1;
        setAhead(180 * moveDir);
    }

    public void onHitWall(HitWallEvent e) {
        moveDir *= -1;
        setBack(140);
        setTurnRight(90);
    }

    public void onHitRobot(HitRobotEvent e) {

        if (isTeammate(e.getName())) {
            setBack(140);
            setTurnRight(90);
            return;
        }

        target = e.getName();
        setBack(140);
        setFire(2.5);
    }

    public void onRobotDeath(RobotDeathEvent e) {
        enemies.remove(e.getName());

        if (e.getName().equals(target)) {
            target = null;
        }
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

    private double guessFactorAngle(Enemy en, int d, int v, int w, double bulletVelocity) {
        double[] segment = gunStats[d][v][w];
        int best = MID;

        for (int i = 0; i < BINS; i++) {
            if (segment[i] > segment[best]) {
                best = i;
            }
        }

        double gf = (double)(best - MID) / MID;
        return en.absBearing + en.direction * gf * maxEscapeAngle(bulletVelocity);
    }

    private double knnAngle(Enemy en, double bulletVelocity) {
        if (history.size() < 12) {
            return en.absBearing;
        }

        ArrayList<Neighbor> neighbors = new ArrayList<>();

        for (Snapshot s : history) {
            double dist =
                    square((en.distance - s.distance) / 800.0) +
                    square((Math.abs(en.velocity) - Math.abs(s.velocity)) / 8.0) +
                    square((en.lateralVelocity - s.lateralVelocity) / 8.0) +
                    square((en.advancingVelocity - s.advancingVelocity) / 8.0) +
                    square((wallIndex(en.x, en.y) - s.wallIndex) / 5.0);

            neighbors.add(new Neighbor(dist, s.guessFactor));
        }

        Collections.sort(neighbors);

        int k = Math.min(18, neighbors.size());
        double weighted = 0;
        double weightSum = 0;

        for (int i = 0; i < k; i++) {
            Neighbor n = neighbors.get(i);
            double weight = 1.0 / (0.05 + n.distance);
            weighted += n.guessFactor * weight;
            weightSum += weight;
        }

        double gf = weightSum == 0 ? 0 : weighted / weightSum;
        gf = limit(-1, gf, 1);

        return en.absBearing + en.direction * gf * maxEscapeAngle(bulletVelocity);
    }

    private double linearAngle(ScannedRobotEvent e, Enemy en, double bulletVelocity) {
        double px = en.x;
        double py = en.y;

        for (int t = 0; t < 90; t++) {
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

    private void addSnapshot(ScannedRobotEvent e, double absBearing, Enemy en) {
        Snapshot s = new Snapshot();
        s.distance = e.getDistance();
        s.velocity = e.getVelocity();
        s.lateralVelocity = en.lateralVelocity;
        s.advancingVelocity = en.advancingVelocity;
        s.wallIndex = wallIndex(en.x, en.y);

        s.guessFactor = limit(-1, en.lateralVelocity / 8.0, 1);

        history.add(s);

        if (history.size() > 2500) {
            history.remove(0);
        }
    }

    private double chooseFirePower(double distance, double enemyEnergy) {
        double power;

        if (getOthers() > 4) power = distance < 250 ? 1.35 : 0.95;
        else if (getOthers() > 2) power = distance < 300 ? 1.65 : 1.15;
        else if (getOthers() == 2) power = distance < 280 ? 2.05 : 1.4;
        else power = distance < 180 ? 3.0 : distance < 450 ? 2.25 : 1.55;

        if (getEnergy() < 25) power = Math.min(power, 1.1);
        if (enemyEnergy < 12) power = Math.min(power, 1.5);

        return limit(0.75, power, 3.0);
    }

    private double wallSmoothing(double x, double y, double angle, int orientation) {
        double stick = 150;

        for (int i = 0; i < 160; i++) {
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
        return (int) limit(0, d / 140, 5);
    }

    private int velocityIndex(double v) {
        return (int) limit(0, v / 2, 4);
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
        double lateralVelocity, advancingVelocity, absBearing;
        int direction = 1;
        long lastSeen;
    }

    static class EnemyWave {
        Point2D.Double fireLocation;
        long fireTime;
        double bulletVelocity;
        double distanceTraveled;
        double directAngle;
        int direction;
        int distanceIndex;
        int velocityIndex;
        int wallIndex;
    }

    static class MyWave {
        Point2D.Double fireLocation;
        long fireTime;
        double bulletVelocity;
        double distanceTraveled;
        double directAngle;
        int direction;
        String targetName;
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