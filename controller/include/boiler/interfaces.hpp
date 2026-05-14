#pragma once
#include "boiler/types.hpp"

// Interfaces d'abstraction matérielle / supervision.
//
// Le cœur de régulation (BoilerController) ne connaît QUE ces interfaces.
// Les implémentations concrètes (GPIO ESP32, relais Raspberry Pi, fakes de
// test) vivent en dehors du cœur — c'est ce qui rend le cœur portable et
// testable sur PC.

namespace boiler {

// Couche actionneurs : pilotage des organes de la chaudière.
struct IActuators {
    // Met un organe en marche (true) ou à l'arrêt (false).
    // Le contrôleur n'appelle cette méthode que sur changement d'état.
    virtual void set_engine(Engine engine, bool on) = 0;

    // Mise à l'arrêt sûre et immédiate de tous les organes de combustion.
    // Doit pouvoir être appelée à tout moment sans précondition.
    virtual void shutdown() = 0;

    virtual ~IActuators() = default;
};

// Couche supervision : journalisation et alertes. Découplée du cœur pour que
// la régulation ne dépende pas de la manière dont on remonte l'information
// (logs, Elasticsearch, SMS, e-mail, MQTT...).
struct IReporter {
    // Appelée à chaque changement d'état de la machine à états.
    virtual void on_state_change(State from, State to, float temperature_celsius) = 0;

    // Appelée à chaque événement de sécurité notable.
    virtual void on_alert(Alert alert, float temperature_celsius) = 0;

    virtual ~IReporter() = default;
};

}  // namespace boiler
