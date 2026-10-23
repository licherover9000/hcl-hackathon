import React from 'react';
import { render, screen } from '@testing-library/react';
import App from './App';

// Mock fetch so the test never depends on a running backend.
beforeEach(() => {
  global.fetch = jest.fn(() =>
    Promise.resolve({
      ok: true,
      json: () => Promise.resolve({ status: 'UP' })
    })
  );
});

afterEach(() => {
  delete global.fetch;
});

test('renders App and shows the API health status', async () => {
  render(<App />);

  // findByText waits for the health call to resolve and fails if the text never appears.
  expect(await screen.findByText('UP')).toBeTruthy();
  expect(global.fetch).toHaveBeenCalledWith(expect.stringMatching(/\/health$/));
});
