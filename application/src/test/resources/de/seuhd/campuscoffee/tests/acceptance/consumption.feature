Feature: Member coffee consumption
  A member bumps their own coffee count via their secret capability token.

  Scenario: A member adds a coffee
    Given the member "maxmustermann"
    When the member adds a coffee
    Then the request succeeds and the coffee count is 1

  Scenario: A member adds two coffees
    Given the member "maxmustermann"
    When the member adds a coffee
    And the member adds a coffee
    Then the request succeeds and the coffee count is 2

  Scenario: A member undoes a coffee within the grace period
    Given the member "maxmustermann"
    When the member adds a coffee
    And the member undoes a coffee
    Then the request succeeds and the coffee count is 0

  Scenario: A member cannot undo when there is nothing to undo
    Given the member "maxmustermann"
    When the member undoes a coffee
    Then the request is rejected with status 409
