Feature: Member coffee consumption
  A member bumps their own coffee count via their secret capability token.

  Scenario: A member increments their coffee count
    Given the member "maxmustermann"
    When the member adds a coffee
    Then the request succeeds and the coffee total is 1

  Scenario: A member increments twice
    Given the member "maxmustermann"
    When the member adds a coffee
    And the member adds a coffee
    Then the request succeeds and the coffee total is 2

  Scenario: A member cannot go below zero
    Given the member "maxmustermann"
    When the member removes a coffee
    Then the request is rejected with status 409
