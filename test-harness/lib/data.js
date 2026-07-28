'use strict';

// Deterministic seeded fake account data. No randomness at request time so
// aggregation runs are perfectly repeatable across pages/runs.

const FIRST = ['Ava', 'Liam', 'Noah', 'Emma', 'Olivia', 'Elijah', 'Sophia', 'Mia',
  'James', 'Amelia', 'Ben', 'Lucas', 'Henry', 'Ella', 'Grace', 'Leo', 'Nora',
  'Jack', 'Aria', 'Owen', 'Luna', 'Levi', 'Chloe', 'Isaac', 'Zoe'];
const LAST = ['Smith', 'Johnson', 'Williams', 'Brown', 'Jones', 'Garcia', 'Miller',
  'Davis', 'Rodriguez', 'Martinez', 'Hernandez', 'Lopez', 'Gonzalez', 'Wilson',
  'Anderson', 'Thomas', 'Taylor', 'Moore', 'Jackson', 'Martin'];
const STATUS = ['Active', 'Active', 'Active', 'Suspended', 'Invited'];
const DEPTS = ['Engineering', 'Sales', 'Finance', 'HR', 'Marketing', 'Support', 'Legal'];

function buildAccounts(total) {
  const rows = [];
  for (let i = 0; i < total; i++) {
    const first = FIRST[i % FIRST.length];
    const last = LAST[(i * 7) % LAST.length];
    const num = String(i + 1).padStart(4, '0');
    rows.push({
      id: i + 1,
      name: `${first} ${last}`,
      email: `${first}.${last}${num}@demo.local`.toLowerCase(),
      department: DEPTS[(i * 3) % DEPTS.length],
      status: STATUS[i % STATUS.length],
      lastSignIn: `2026-0${(i % 9) + 1}-${String((i % 27) + 1).padStart(2, '0')}`,
    });
  }
  return rows;
}

const ACCOUNTS = buildAccounts(247);

module.exports = { ACCOUNTS };
