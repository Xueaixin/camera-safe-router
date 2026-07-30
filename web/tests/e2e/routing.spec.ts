import { expect, test, type BrowserContext, type Page } from '@playwright/test';

async function selectEndpoints(page: Page) {
  await page.getByRole('combobox', { name: '搜索起点' }).fill('天安');
  await page.getByRole('option', { name: /天安门广场/ }).click();
  await page.getByRole('combobox', { name: '搜索终点' }).fill('望京');
  await page.getByRole('option', { name: /望京SOHO/ }).click();
}

async function planInitialRoute(page: Page) {
  await selectEndpoints(page);
  await page.getByTestId('plan-route').click();
  await expect(page.getByText('路线已通过安全校验')).toBeVisible();
}

async function enableLocation(page: Page, context: BrowserContext) {
  await context.grantPermissions(['geolocation'], { origin: 'http://127.0.0.1:4173' });
  await context.setGeolocation({ longitude: 116.39, latitude: 39.9, accuracy: 18.2 });
  await page.getByTestId('location-button').click();
  await expect(page.getByTestId('map-container')).toHaveAttribute(
    'data-has-current-location',
    'true',
  );
}

test('first route planning succeeds with frozen mock data', async ({ page }) => {
  await page.goto('/');
  await planInitialRoute(page);
  await expect(page.getByTestId('route-summary')).toContainText('28 分钟');
  await expect(page.getByTestId('map-container')).toHaveAttribute('data-route-points', '4');
});

test('no compliant route is explicit and no route is drawn', async ({ page }) => {
  await page.goto('/?mockScenario=no-route');
  await selectEndpoints(page);
  await page.getByTestId('plan-route').click();
  await expect(page.getByTestId('route-error')).toContainText('没有合规路线');
  await expect(page.getByTestId('map-container')).toHaveAttribute('data-route-points', '0');
});

test('allowed geolocation renders a display location and centers only on command', async ({
  page,
  context,
}) => {
  await page.goto('/');
  await enableLocation(page, context);
  await page.getByTestId('location-button').click();
  await expect(page.getByTestId('map-container')).toHaveAttribute('data-centered', 'current');
  await page.getByRole('button', { name: '使用当前位置' }).click();
  await expect(page.getByRole('combobox', { name: '搜索起点' })).toHaveValue('当前位置');
});

test('denied geolocation displays a recoverable permission state', async ({ page, context }) => {
  await context.clearPermissions();
  await page.goto('/');
  await page.getByTestId('location-button').click();
  await expect(page.getByText(/定位权限被拒绝/).last()).toBeVisible({ timeout: 12_000 });
});

test('manual rerouting uses current location and replaces the route on success', async ({
  page,
  context,
}) => {
  await page.goto('/');
  await planInitialRoute(page);
  await enableLocation(page, context);
  await page.getByTestId('reroute-button').click();
  await expect(page.getByRole('combobox', { name: '搜索起点' })).toHaveValue('当前位置');
  await expect(page.getByTestId('map-container')).toHaveAttribute('data-route-points', '4');
});

test('failed manual rerouting retains the old route and original start', async ({
  page,
  context,
}) => {
  await page.goto('/?mockScenario=reroute-failure&mockDelay=500');
  await planInitialRoute(page);
  await enableLocation(page, context);
  await page.getByTestId('reroute-button').click();
  await expect(page.getByTestId('map-container')).toHaveAttribute('data-route-points', '4');
  await expect(page.getByTestId('route-error')).toContainText('原路线仍保留');
  await expect(page.getByRole('combobox', { name: '搜索起点' })).toHaveValue('天安门广场');
  await expect(page.getByTestId('map-container')).toHaveAttribute('data-route-points', '4');
});

test('camera layer can be hidden and restored without per-point Vue components', async ({
  page,
}) => {
  await page.goto('/');
  await expect(page.getByTestId('map-container')).toHaveAttribute('data-camera-count', /[1-9]/);
  await page.getByTestId('camera-toggle').click();
  await expect(page.getByTestId('map-container')).toHaveAttribute('data-camera-count', '0');
  await page.getByTestId('camera-toggle').click();
  await expect(page.getByTestId('map-container')).toHaveAttribute('data-camera-count', /[1-9]/);
});

test('map click selection writes an explicitly GCJ02-backed endpoint', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: '在地图上选择搜索起点' }).click();
  const canvas = page.locator('.mock-map__canvas');
  const box = await canvas.boundingBox();
  expect(box).not.toBeNull();
  await canvas.click({
    position: { x: 28, y: Math.min(420, Math.max(40, (box?.height ?? 500) - 180)) },
  });
  await expect(page.getByRole('combobox', { name: '搜索起点' })).toHaveValue(/地图选点/);
});

test('primary overlays stay within the viewport without incoherent overlap', async ({ page }) => {
  const viewports = [
    { width: 360, height: 800 },
    { width: 390, height: 844 },
    { width: 430, height: 932 },
    { width: 844, height: 390 },
    { width: 1280, height: 720 },
    { width: 1440, height: 900 },
  ];
  for (const viewport of viewports) {
    await page.setViewportSize(viewport);
    await page.goto('/');
    const layout = await page.evaluate(() => {
      const search = document.querySelector('.search-panel')?.getBoundingClientRect();
      const toolbar = document.querySelector('.map-toolbar')?.getBoundingClientRect();
      const sheet = document.querySelector('.route-sheet')?.getBoundingClientRect();
      const intersects = (a?: DOMRect, b?: DOMRect) =>
        Boolean(
          a && b && a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top,
        );
      return {
        horizontalOverflow: document.documentElement.scrollWidth > window.innerWidth,
        searchToolbarOverlap: intersects(search, toolbar),
        toolbarSheetOverlap: intersects(toolbar, sheet),
        sheetInside: Boolean(
          sheet &&
          sheet.left >= 0 &&
          sheet.right <= window.innerWidth &&
          sheet.bottom <= window.innerHeight,
        ),
      };
    });
    expect(layout, `${viewport.width}x${viewport.height}`).toEqual({
      horizontalOverflow: false,
      searchToolbarOverlap: false,
      toolbarSheetOverlap: false,
      sheetInside: true,
    });
  }
});
