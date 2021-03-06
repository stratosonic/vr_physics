package virtual_robot.controller.robots;

import com.qualcomm.robotcore.hardware.ServoImpl;
import javafx.fxml.FXML;
import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.*;
import javafx.scene.transform.Rotate;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.hardware.bosch.BNO055IMUImpl;
import com.qualcomm.robotcore.hardware.DcMotorImpl;
import com.qualcomm.robotcore.hardware.MotorType;
import javafx.scene.transform.Translate;
import util3d.Parts;
import util3d.Util3D;
import virtual_robot.controller.BotConfig;
import virtual_robot.controller.VirtualBot;
import virtual_robot.controller.VirtualRobotController;
import virtual_robot.util.AngleUtils;

/**
 * For internal use only. Represents a robot with four mechanum wheels, color sensor, four distance sensors,
 * a BNO055IMU, and a Servo-controlled arm on the back.
 *
 * MechanumBot is the controller class for the "mechanum_bot.fxml" markup file.
 *
 */
//@BotConfig(name = "Mechanum Bot")
public abstract class MechanumBot extends VirtualBot {

    public final MotorType motorType = MotorType.Neverest40;
    private DcMotorImpl[] motors = null;
    private DcMotorImpl armExtensionMotor = null;
    private DcMotorImpl armRotationMotor = null;
    private BNO055IMUImpl imu = null;
    private VirtualRobotController.ColorSensorImpl colorSensor = null;
    private ServoImpl fingerServo = null;
    private VirtualRobotController.DistanceSensorImpl[] distanceSensors = null;

    private double wheelCircumference;
    private double interWheelWidth;
    private double interWheelLength;
    private double wlAverage;

    private double[][] tWR; //Transform from wheel motion to robot motion

    Rotate armRotate = new Rotate(0, 0, -5.5, 0, new Point3D(1, 0, 0));
    Translate midArmTranslate = new Translate(0, 0, 0);
    Translate foreArmTranslate = new Translate(0, 0, 0);
    Translate leftFingerTranslate = new Translate(0, 0, 0);
    Translate rightFingerTranslate = new Translate(0, 0, 0);
    Rotate[] wheelRotates = new Rotate[] {new Rotate(0, Rotate.Y_AXIS), new Rotate(0, Rotate.Y_AXIS), new Rotate(0, Rotate.Y_AXIS), new Rotate(0, Rotate.Y_AXIS)};

    double armRotation = 0;
    double armExtension = 0;
    double[] wheelRotations = new double[]{0,0,0,0};

    /**
     *  Overrides the init() method of VirtualBot. The override method must call super.init() as its first statement.
     */
    @Override
    public void init(){
        //This call ensures that the hardware map and display group get created
        super.init();

        //Everything else in this method is for convenience. e.g., all of the hardware references could be
        //repeatedly obtained from the hardware map within updateStateAndSensors()
        motors = new DcMotorImpl[]{
                (DcMotorImpl)hardwareMap.dcMotor.get("back_left_motor"),
                (DcMotorImpl)hardwareMap.dcMotor.get("front_left_motor"),
                (DcMotorImpl)hardwareMap.dcMotor.get("front_right_motor"),
                (DcMotorImpl)hardwareMap.dcMotor.get("back_right_motor")
        };

        armExtensionMotor = (DcMotorImpl)hardwareMap.dcMotor.get("arm_extension_motor");
        armRotationMotor = (DcMotorImpl)hardwareMap.dcMotor.get("arm_rotation_motor");

        distanceSensors = new VirtualRobotController.DistanceSensorImpl[]{
                hardwareMap.get(VirtualRobotController.DistanceSensorImpl.class, "front_distance"),
                hardwareMap.get(VirtualRobotController.DistanceSensorImpl.class, "left_distance"),
                hardwareMap.get(VirtualRobotController.DistanceSensorImpl.class, "back_distance"),
                hardwareMap.get(VirtualRobotController.DistanceSensorImpl.class, "right_distance")
        };
        imu = hardwareMap.get(BNO055IMUImpl.class, "imu");
        colorSensor = (VirtualRobotController.ColorSensorImpl)hardwareMap.colorSensor.get("color_sensor");
        fingerServo = (ServoImpl)hardwareMap.servo.get("finger_servo");
        wheelCircumference = Math.PI * botWidth / 4.5;
        interWheelWidth = botWidth * 8.0 / 9.0;
        interWheelLength = botWidth * 7.0 / 9.0;
        wlAverage = (interWheelLength + interWheelWidth) / 2.0;

        tWR = new double[][] {
                {-0.25, 0.25, -0.25, 0.25},
                {0.25, 0.25, 0.25, 0.25},
                {-0.25/ wlAverage, -0.25/ wlAverage, 0.25/ wlAverage, 0.25/ wlAverage},
                {-0.25, 0.25, 0.25, -0.25}
        };
    }

    /**
     *  Create the hardware map for this robot.
     */
    protected void createHardwareMap(){
        hardwareMap = new HardwareMap();
        String[] driveMotorNames = new String[] {"back_left_motor", "front_left_motor", "front_right_motor", "back_right_motor"};
        for (String name: driveMotorNames) hardwareMap.put(name, new DcMotorImpl(motorType));
        hardwareMap.put("arm_rotation_motor", new DcMotorImpl(motorType, false, false));
        hardwareMap.put("arm_extension_motor", new DcMotorImpl(motorType, false, false));
        String[] distNames = new String[]{"front_distance", "left_distance", "back_distance", "right_distance"};
        for (String name: distNames) hardwareMap.put(name, controller.new DistanceSensorImpl());
        hardwareMap.put("imu", new BNO055IMUImpl(this, 10));
        hardwareMap.put("color_sensor", controller.new ColorSensorImpl());
        hardwareMap.put("finger_servo", new ServoImpl());
    }

    /**
     * Update robot state (including the state of all sensors after a time interval millis.
     *
     * NOTE: This method should update VARIABLES that represent the robot state, but should not actually
     * update the robot display itself, because it is called from a non-UI thread. Instead, the variables
     * that are updated in the updateStateAndSensors() method should be used in the updateDisplay()
     * method to actually update the display.
     *
     * @param millis milliseconds since the previous update
     */
    public synchronized void updateStateAndSensors(double millis){

        double[] deltaPos = new double[4];
        double[] w = new double[4];

        for (int i = 0; i < 4; i++) {
            deltaPos[i] = motors[i].update(millis);
            w[i] = deltaPos[i] * wheelCircumference / motorType.TICKS_PER_ROTATION;
            double wheelRotationDegrees = 360.0 * deltaPos[i] / motorType.TICKS_PER_ROTATION;
            if (i < 2) {
                w[i] = -w[i];
                wheelRotationDegrees = -wheelRotationDegrees;
            }
            wheelRotations[i] += Math.min(17, Math.max(-17, wheelRotationDegrees));
        }

        double[] robotDeltaPos = new double[] {0,0,0,0};
        for (int i=0; i<4; i++){
            for (int j = 0; j<4; j++){
                robotDeltaPos[i] += tWR[i][j] * w[j];
            }
        }

//        double dxR = robotDeltaPos[0];
//        double dyR = robotDeltaPos[1];
//        double headingChange = robotDeltaPos[2];
//        double avgHeading = headingRadians + headingChange / 2.0;
//
//        double sin = Math.sin(avgHeading);
//        double cos = Math.cos(avgHeading);
//
//        x += dxR * cos - dyR * sin;
//        y += dxR * sin + dyR * cos;
//        headingRadians += headingChange;
//
//        if (x >  (halfFieldWidth - halfBotWidth)) x = halfFieldWidth - halfBotWidth;
//        else if (x < (halfBotWidth - halfFieldWidth)) x = halfBotWidth - halfFieldWidth;
//        if (y > (halfFieldWidth - halfBotWidth)) y = halfFieldWidth - halfBotWidth;
//        else if (y < (halfBotWidth - halfFieldWidth)) y = halfBotWidth - halfFieldWidth;
//
//        if (headingRadians > Math.PI) headingRadians -= 2.0 * Math.PI;
//        else if (headingRadians < -Math.PI) headingRadians += 2.0 * Math.PI;
//
//        imu.updateHeadingRadians(headingRadians);
//
//        colorSensor.updateColor(x, y);
//
//        final double piOver2 = Math.PI / 2.0;
//
//        for (int i = 0; i<4; i++){
//            double sensorHeading = AngleUtils.normalizeRadians(headingRadians + i * piOver2);
//            distanceSensors[i].updateDistance( x - halfBotWidth * Math.sin(sensorHeading),
//                    y + halfBotWidth * Math.cos(sensorHeading), sensorHeading);
//        }

        double deltaArmRotMotorTicks = armRotationMotor.update(millis);
        double newArmRotation = armRotation + 0.05 * deltaArmRotMotorTicks;
        armRotation = Math.max(0, Math.min(90, newArmRotation));
        double newArmExtension = armExtension + 0.01 * armExtensionMotor.update(millis);
        armExtension = Math.max(0, Math.min(22, newArmExtension));

    }

    /**
     * Create (and return) the 3D display group for this robot.
     * @return javafx 3D display group
     */
    protected Group getDisplayGroup(){
        Group chassis = new Group();
        Group leftRail = Parts.tetrixBox(2, 18, 2, 2);
        leftRail.setTranslateX(-6);
        Group rightRail = Parts.tetrixBox(2, 18, 2, 2);
        rightRail.setTranslateX(6);
        Group frontRail = Parts.tetrixBox(2, 10, 2, 2);
        frontRail.getTransforms().addAll(new Translate(0, 8, 0), new Rotate(90, new Point3D(0,0,1)));
        Group backRail = Parts.tetrixBox(2, 10, 2, 2);
        backRail.getTransforms().addAll(new Translate(0, -8, 0), new Rotate(90, new Point3D(0, 0, 1)));
        Box center = new Box(10, 14, 2);
        center.setMaterial(new PhongMaterial(Color.YELLOW));

        chassis.getChildren().addAll(leftRail, rightRail, frontRail, backRail, center);

        Group[] wheels = new Group[4];
        for (int i=0; i<4; i++){
            wheels[i] = Parts.mecanumWheel(4, 2, i);
            wheels[i].setRotationAxis(new Point3D(0, 0, 1));
            wheels[i].setRotate(90);
            wheels[i].setTranslateX(i<2? -8 : 8);
            wheels[i].setTranslateY(i==0 || i==3? -7 : 7);
            wheels[i].getTransforms().add(wheelRotates[i]);
        }

        PhongMaterial armMaterial = new PhongMaterial(Color.FUCHSIA);
        armMaterial.setSpecularColor(Color.WHITE);
        Box arm = new Box(1, 12, 1);
        arm.setMaterial(armMaterial);
        Box midArm = new Box(1, 12, 1);
        midArm.setMaterial(armMaterial);
        Box foreArm = new Box(1, 12, 1);
        foreArm.setMaterial(armMaterial);
        Box hand = new Box(6, 1, 1);
        hand.setTranslateY(6);
        hand.setMaterial(armMaterial);
        Box  leftFinger = new Box(1, 4, 1);
        leftFinger.setTranslateY(8);
        leftFinger.setTranslateX(-2.5);
        leftFinger.setMaterial(armMaterial);
        Box  rightFinger = new Box(1, 4, 1);
        rightFinger.setTranslateY(8);
        rightFinger.setTranslateX(2.5);
        rightFinger.setMaterial(armMaterial);
        leftFinger.getTransforms().add(leftFingerTranslate);
        rightFinger.getTransforms().add(rightFingerTranslate);
        Group foreArmGroup = new Group(foreArm, hand, leftFinger, rightFinger);
        foreArmGroup.setTranslateZ(1);
        foreArmGroup.getTransforms().add(foreArmTranslate);
        Group midArmGroup = new Group(midArm, foreArmGroup);
        midArmGroup.setTranslateZ(1);
        midArmGroup.getTransforms().add(midArmTranslate);
        Group armGroup = new Group(arm, midArmGroup);
        armGroup.setTranslateZ(1.5);
        armGroup.setTranslateY(-3);
        armGroup.getTransforms().add(armRotate);


        Group botGroup = new Group();
        botGroup.getChildren().add(chassis);
        botGroup.getChildren().addAll(wheels);
        botGroup.getChildren().add(armGroup);
        botGroup.setTranslateZ(2);
        return botGroup;
    }

    /**
     *  Update the display of the robot based upon whatever changes have occurred during the last call to
     *  updateStateAndSensors. The first statement of this method must be: super.updateDisplay() -- this call
     *  updates the position and orientation of the robot on the field. All other changes in robot appearance
     *  must be coded in this updateDisplay() method. This method will be run on the Application (i.e., UI)
     *  thread via a call to Platform.runLater(...).
     */
    @Override
    public synchronized void updateDisplay(){
        super.updateDisplay();
        armRotate.setAngle(armRotation);
        midArmTranslate.setY(armExtension/2.0);
        foreArmTranslate.setY(armExtension/2.0);
        double fingerMovement = fingerServo.getInternalPosition();
        leftFingerTranslate.setX(fingerMovement);
        rightFingerTranslate.setX(-fingerMovement);
        for (int i=0; i<4; i++){
            wheelRotates[i].setAngle(wheelRotations[i]);
        }
    }


    public void powerDownAndReset(){
        for (int i=0; i<4; i++) motors[i].stopAndReset();
        imu.close();
    }


}
