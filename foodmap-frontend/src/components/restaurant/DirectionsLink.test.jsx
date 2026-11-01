import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import DirectionsLink from './DirectionsLink';

const PHO_HOA = { id: 1, name: 'Phở Hòa Pasteur', latitude: 10.7762, longitude: 106.6892 };

describe('DirectionsLink — "Chỉ đường"', () => {
  it('valid coordinates: a link named "Chỉ đường đến {tên quán}" to those coordinates, in a new tab', () => {
    render(<DirectionsLink restaurant={PHO_HOA} />);
    const link = screen.getByRole('link', { name: 'Chỉ đường đến Phở Hòa Pasteur' });
    expect(link).toHaveAttribute('href', 'https://www.google.com/maps/dir/?api=1&destination=10.7762%2C106.6892');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(link).toHaveAccessibleDescription('Mở Google Maps trong thẻ mới.');
    expect(link).toHaveTextContent(/^Chỉ đường/);                 // the visible words start the name
  });

  it('a target of at least 44 px: the md button size (h-11)', () => {
    render(<DirectionsLink restaurant={PHO_HOA} />);
    const link = screen.getByRole('link', { name: /^Chỉ đường/ });
    expect(link.className).toMatch(/\bh-11\b/);
  });

  it.each([
    ['missing latitude', { ...PHO_HOA, latitude: undefined }],
    ['missing longitude', { ...PHO_HOA, longitude: undefined }],
    ['invalid latitude', { ...PHO_HOA, latitude: 123 }],
    ['invalid longitude', { ...PHO_HOA, longitude: -200 }],
  ])('%s: nothing rendered (no broken link)', (_, restaurant) => {
    const { container } = render(<DirectionsLink restaurant={restaurant} />);
    expect(container).toBeEmptyDOMElement();
  });
});
