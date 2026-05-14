#pragma once
#include <Arduino.h>
#include "boiler/interfaces.hpp"
#include "pins.hpp"

// Implémentation ESP32 de IActuators : pilotage des organes via les GPIO.
//
// Chaîne : GPIO → ULN2803 → SSR (vis) / carte relais (autres). Le câblage est
// fait pour que **niveau HAUT = organe en marche** (voir docs/hardware.md §3.6).
// On mémorise aussi l'état désiré de chaque organe pour l'UI web.
class Esp32Actuators : public boiler::IActuators {
public:
    // À appeler une fois dans setup(), avant toute régulation.
    void begin() {
        for (int pin : kPins) {
            pinMode(pin, OUTPUT);
            digitalWrite(pin, LOW);   // tout à l'arrêt
        }
        for (bool& s : state_) s = false;
    }

    void set_engine(boiler::Engine engine, bool on) override {
        const int idx = static_cast<int>(engine);
        state_[idx] = on;
        digitalWrite(kPins[idx], on ? HIGH : LOW);
    }

    void shutdown() override {
        for (int i = 0; i < kCount; ++i) {
            state_[i] = false;
            digitalWrite(kPins[i], LOW);
        }
    }

    // Lecture seule pour la supervision (UI web).
    bool engine(boiler::Engine e) const { return state_[static_cast<int>(e)]; }

private:
    static constexpr int kCount = 5;
    // Indexé par la valeur de l'énumération boiler::Engine.
    static constexpr int kPins[kCount] = {
        pins::TREMIE, pins::BRULEUR, pins::VENTILO,
        pins::CIRCULATEUR, pins::LADDOMAT,
    };
    bool state_[kCount] = {false, false, false, false, false};
};
