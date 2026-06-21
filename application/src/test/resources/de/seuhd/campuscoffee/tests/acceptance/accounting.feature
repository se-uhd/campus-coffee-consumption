Feature: Coffee money and balances
  An admin sets the price; a member's balance reflects the coffees they drink, the beans they buy, and the
  settlements they pay. All money is in euro cents; a negative balance means the member owes the fund.

  Scenario: A coffee is valued at the price in effect when consumed
    Given the coffee member "maxmustermann"
    And an admin sets the price to 50 cents
    When the member drinks a coffee
    And an admin sets the price to 70 cents
    And the member drinks a coffee
    Then the member's balance is -120 cents

  Scenario: A member purchase credits the balance
    Given the coffee member "maxmustermann"
    When the member buys beans for 900 cents
    Then the member's balance is 900 cents

  Scenario: A settlement credits the balance
    Given the coffee member "maxmustermann"
    And an admin sets the price to 50 cents
    When the member drinks a coffee
    And an admin records a 1000 cent settlement for the member
    Then the member's balance is 950 cents
