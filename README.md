# SensorLogger
Stand alone sensor logger for Android Wear

## logging format
* file distination: "SensorLogger" directory in external storage
 * generally: /mnt/shell/emulated/0/SensorLogger
* file name: YYYYMMDD_hhmmss_nnn.txt
 * YYYY: year
 * MM: Month
 * DD: Day
 * hh: hour
 * mm: minute
 * ss: second
 * nnn: counted up number
* Files will be devided at some each interval with nnn postfix.
* file format is following

~~~
time timestamp wx wy wz gx gy gz mx my mz na na na na na Ts
~~~
 * msec: time of msec at writing (Hexiadecimal) [msec]
 * timestamp: time stamp for sensor data (Hexiadecimal) [usec]
 * wx, wy, wz: Angular velocity [rad/sec]
 * gx, gy, gz: Acceleration [G]
 * mx, my, mz: Magnetic force [Gauss]
