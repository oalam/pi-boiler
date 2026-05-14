#pragma once
#include <Arduino.h>
#include "boiler/interfaces.hpp"

// Noms lisibles des états et alertes — partagés avec l'UI web.
inline const char* state_name(boiler::State s) {
    switch (s) {
        case boiler::State::Stopped:     return "Stopped";
        case boiler::State::Igniting:    return "Igniting";
        case boiler::State::Running:     return "Running";
        case boiler::State::Slowing:     return "Slowing";
        case boiler::State::Overheating: return "Overheating";
        case boiler::State::Locked:      return "Locked";
    }
    return "?";
}

inline const char* alert_name(boiler::Alert a) {
    switch (a) {
        case boiler::Alert::Overheat:              return "Overheat";
        case boiler::Alert::LockoutIgnitionFailed: return "LockoutIgnitionFailed";
        case boiler::Alert::LockoutLowTemp:        return "LockoutLowTemp";
        case boiler::Alert::SensorFailure:         return "SensorFailure";
        case boiler::Alert::EngineFault:           return "EngineFault";
    }
    return "?";
}

// Implémentation ESP32 de IReporter : journalisation série et mémorisation du
// dernier état / dernière alerte pour l'UI web.
// Évolution (phase 3) : publication MQTT vers le superviseur Raspberry Pi.
class Esp32Reporter : public boiler::IReporter {
public:
    void on_state_change(boiler::State from, boiler::State to, float temp) override {
        last_state_ = to;
        Serial.printf("[STATE] %s -> %s  (%.1f C)\n",
                      state_name(from), state_name(to), temp);
    }

    void on_alert(boiler::Alert alert, float temp) override {
        last_alert_ = alert;
        ++alert_count_;
        Serial.printf("[ALERT] %s  (%.1f C)\n", alert_name(alert), temp);
    }

    boiler::State last_state() const { return last_state_; }
    boiler::Alert last_alert() const { return last_alert_; }
    uint32_t      alert_count() const { return alert_count_; }

private:
    boiler::State last_state_ = boiler::State::Stopped;
    boiler::Alert last_alert_ = boiler::Alert::Overheat;
    uint32_t      alert_count_ = 0;
};
