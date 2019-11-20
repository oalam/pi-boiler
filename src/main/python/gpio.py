# External module imports
import RPi.GPIO as GPIO
import time

# Pin Definitons:
tremiePin = 4       # Vis sans fin tremie
bruleurPin = 17     # Vis sans fin bruleur
ventiloPin = 22     # Ventilateur
vanne4VPin = 18     # Vanne 4 voies
laddomatPin = 23    # Pompe de charge Laddomat
tremieSecuPin = 24  # Coupe circuit tremie
bruleurSecuPin = 25 # coupe circuit bruleur



dc = 95 # duty cycle (0-100) for PWM pin

# Pin Setup:
GPIO.setmode(GPIO.BCM) # Broadcom pin-numbering scheme
GPIO.setup(tremiePin, GPIO.OUT) # set as output
GPIO.setup(bruleurPin, GPIO.OUT) # set as output
GPIO.setup(ventiloPin, GPIO.OUT) # set as output
GPIO.setup(vanne4VPin, GPIO.OUT) # set as output
GPIO.setup(laddomatPin, GPIO.OUT) # set as output


GPIO.setup(tremieSecuPin, GPIO.IN, pull_up_down=GPIO.PUD_UP)
GPIO.setup(bruleurSecuPin, GPIO.IN, pull_up_down=GPIO.PUD_UP)


#pwm = GPIO.PWM(pwmPin, 50)  # Initialize PWM on pwmPin 100Hz frequency
#GPIO.setup(butPin, GPIO.IN, pull_up_down=GPIO.PUD_UP) # Button pin set as input w/ pull-up

# Initial state for LEDs:
GPIO.output(tremiePin, GPIO.LOW)
GPIO.output(bruleurPin, GPIO.LOW)
GPIO.output(ventiloPin, GPIO.LOW)
GPIO.output(vanne4VPin, GPIO.LOW)
GPIO.output(laddomatPin, GPIO.LOW)
#pwm.start(dc)


def securityCheck():
    tremieKO = GPIO.input(tremieSecuPin)
    bruleurKO =  GPIO.input(bruleurSecuPin)
    
    if tremieKO :
        print('Probleme moteur tremie')

    if bruleurKO :
        print('Probleme moteur burleur')

    return tremieKO or bruleurKO


print("Here we go! Press CTRL+C to exit")
try:
    while 1:
        securityCheck()
        #GPIO.output(tremiePin, GPIO.HIGH)
        time.sleep(1)
        #GPIO.output(tremiePin, GPIO.LOW)
        #time.sleep(10)
except KeyboardInterrupt: # If CTRL+C is pressed, exit cleanly:
    GPIO.cleanup() # cleanup all GPIO