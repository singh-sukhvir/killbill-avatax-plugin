/*
 * Copyright 2014-2020 Groupon, Inc
 * Copyright 2020-2021 Equinix, Inc
 * Copyright 2014-2021 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.plugin.avatax.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import org.killbill.billing.account.api.Account;
import org.killbill.billing.catalog.api.CatalogApiException;
import org.killbill.billing.catalog.api.CatalogUserApi;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.catalog.api.StaticCatalog;
import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.invoice.api.InvoiceItemType;
import org.killbill.billing.invoice.api.InvoiceUserApi;
import org.killbill.billing.osgi.libs.killbill.OSGIConfigPropertiesService;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.plugin.TestUtils;
import org.killbill.billing.plugin.api.PluginCallContext;
import org.killbill.billing.plugin.avatax.AvaTaxRemoteTestBase;
import org.killbill.billing.plugin.avatax.core.AvaTaxActivator;
import org.killbill.billing.plugin.avatax.core.AvaTaxConfigurationHandler;
import org.killbill.billing.util.api.CustomFieldUserApi;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.clock.Clock;
import org.killbill.clock.DefaultClock;
import org.mockito.Mockito;
import org.osgi.framework.BundleContext;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

// Note: the test assumes all California authorities are set up to collect sales and use tax (270+)
public class TestAvaTaxInvoicePluginApi extends AvaTaxRemoteTestBase {

    private OSGIKillbillAPI osgiKillbillAPI;
    private Collection<PluginProperty> pluginProperties;
    private AvaTaxInvoicePluginApi avaTaxInvoicePluginApi;
    private Account account;
    private CallContext callContext;

    @BeforeMethod(groups = "integration")
    public void setUp() throws Exception {
        final Clock clock = new DefaultClock();

        pluginProperties = new LinkedList<PluginProperty>();

        // California Nexus must be enabled in your account for the test to pass
        // As of July 2021, tax rates are:
        //   CA STATE TAX: 0.06
        //   CA COUNTY TAX: 0.0025
        //   CA SPECIAL TAX (SAN FRANCISCO CO LOCAL TAX SL): 0.01
        //   CA SPECIAL TAX (SAN FRANCISCO COUNTY DISTRICT TAX SP): 0.01375
        account = TestUtils.buildAccount(Currency.USD, "45 Fremont Street", null, "San Francisco", "CA", "94105", "US");

        callContext = new PluginCallContext(AvaTaxActivator.PLUGIN_NAME, clock.getUTCNow(), account.getId(), UUID.randomUUID());

        osgiKillbillAPI = TestUtils.buildOSGIKillbillAPI(account);
        Mockito.when(osgiKillbillAPI.getCustomFieldUserApi()).thenReturn(Mockito.mock(CustomFieldUserApi.class));
        Mockito.when(osgiKillbillAPI.getInvoiceUserApi()).thenReturn(Mockito.mock(InvoiceUserApi.class));
        final CatalogUserApi catalogUserApi = Mockito.mock(CatalogUserApi.class);
        final StaticCatalog staticCatalog = Mockito.mock(StaticCatalog.class);
        Mockito.doThrow(CatalogApiException.class).when(staticCatalog).findPlan(Mockito.anyString());
        Mockito.when(catalogUserApi.getCurrentCatalog(Mockito.any(), Mockito.<TenantContext>any())).thenReturn(staticCatalog);
        Mockito.when(osgiKillbillAPI.getCatalogUserApi()).thenReturn(catalogUserApi);

        final AvaTaxConfigurationHandler avaTaxConfigurationHandler = new AvaTaxConfigurationHandler(AvaTaxActivator.PLUGIN_NAME,
                                                                                                     osgiKillbillAPI);
        avaTaxConfigurationHandler.setDefaultConfigurable(client);
        avaTaxInvoicePluginApi = new AvaTaxInvoicePluginApi(avaTaxConfigurationHandler,
                                                            dao,
                                                            osgiKillbillAPI,
                                                            new OSGIConfigPropertiesService(Mockito.mock(BundleContext.class)),
                                                            clock);
    }

    @Test(groups = "integration")
    public void testItemAdjustments() throws Exception {
        final Invoice invoice = TestUtils.buildInvoice(account);
        final List<InvoiceItem> invoiceItems = new LinkedList<InvoiceItem>();
        Mockito.when(invoice.getInvoiceItems()).thenReturn(invoiceItems);

        /*
         * Scenario 1A: new item on new invoice
         *     $100 Taxable item I1
         */
        final InvoiceItem taxableItem1 = TestUtils.buildInvoiceItem(invoice, InvoiceItemType.EXTERNAL_CHARGE, new BigDecimal("100"), null);
        invoiceItems.add(taxableItem1);
        pluginProperties.add(new PluginProperty(String.format("%s_%s", AvaTaxTaxCalculator.TAX_CODE, taxableItem1.getId()), "D9999999", false));
        List<InvoiceItem> additionalInvoiceItems = avaTaxInvoicePluginApi.getAdditionalInvoiceItems(invoice, false, pluginProperties, callContext);
        // 4 TAX items expected (total $8.63)
        checkTaxes(additionalInvoiceItems, 4, new BigDecimal("8.63"));

        /*
         * Scenario 1B: re-invoice of 1A (should be idempotent)
         *     $100    Taxable item I1
         *       $8.63 Tax items (x4)
         */
        invoiceItems.addAll(additionalInvoiceItems);
        additionalInvoiceItems = avaTaxInvoicePluginApi.getAdditionalInvoiceItems(invoice, false, pluginProperties, callContext);
        checkTaxes(additionalInvoiceItems, 0, BigDecimal.ZERO);

        /*
         * Scenario 2A: item adjustment on existing invoice
         *     $100    Taxable item I1
         *       $8.63 Tax items (x4)
         *     -$50    Item adjustment I2
         */
        final InvoiceItem itemAdjustment2 = TestUtils.buildInvoiceItem(invoice, InvoiceItemType.ITEM_ADJ, new BigDecimal("-50"), taxableItem1.getId());
        invoiceItems.add(itemAdjustment2);
        additionalInvoiceItems = avaTaxInvoicePluginApi.getAdditionalInvoiceItems(invoice, false, pluginProperties, callContext);
        // 4 TAX items expected (total -$4.32)
        checkTaxes(additionalInvoiceItems, 4, new BigDecimal("-4.32"));

        /*
         * Scenario 2B: re-invoice of 2A (should be idempotent)
         *     $100    Taxable item I1
         *       $8.63 Tax items (x4)
         *     -$50    Item adjustment I2
         *      -$4.32 Tax items (x4)
         */
        invoiceItems.addAll(additionalInvoiceItems);
        additionalInvoiceItems = avaTaxInvoicePluginApi.getAdditionalInvoiceItems(invoice, false, pluginProperties, callContext);
        checkTaxes(additionalInvoiceItems, 0, BigDecimal.ZERO);

        /*
         * Scenario 3A: second item adjustment on existing invoice
         *     $100    Taxable item I1
         *       $8.63 Tax items (x4)
         *     -$50    Item adjustment I2
         *      -$4.32 Tax items (x4)
         *     -$50    Item adjustment I3
         */
        final InvoiceItem itemAdjustment3 = TestUtils.buildInvoiceItem(invoice, InvoiceItemType.ITEM_ADJ, new BigDecimal("-50"), taxableItem1.getId());
        invoiceItems.add(itemAdjustment3);
        additionalInvoiceItems = avaTaxInvoicePluginApi.getAdditionalInvoiceItems(invoice, false, pluginProperties, callContext);
        // 4 TAX items expected (total -$4.32)
        // Note: due to rounding, more tax is returned than initially taxed (the value comes straight from AvaTax).
        // To avoid this, in case of multiple item adjustments, you might have to return tax manually in AvaTax.
        checkTaxes(additionalInvoiceItems, 4, new BigDecimal("-4.32"));

        /*
         * Scenario 3B: re-invoice of 3A (should be idempotent)
         *     $100    Taxable item I1
         *       $8.63 Tax items (x4)
         *     -$50    Item adjustment I2
         *      -$4.32 Tax items (x4)
         *     -$50    Item adjustment I3
         *      -$4.32 Tax items (x4)
         */
        invoiceItems.addAll(additionalInvoiceItems);
        additionalInvoiceItems = avaTaxInvoicePluginApi.getAdditionalInvoiceItems(invoice, false, pluginProperties, callContext);
        checkTaxes(additionalInvoiceItems, 0, BigDecimal.ZERO);
    }

    @Test(groups = "integration")
    public void testRepair() throws Exception {
        final Invoice invoice1 = TestUtils.buildInvoice(account);
        final List<InvoiceItem> invoiceItems1 = new LinkedList<InvoiceItem>();
        Mockito.when(invoice1.getInvoiceItems()).thenReturn(invoiceItems1);

        /*
         * Scenario 1A: new item on new invoice
         *     $100 Taxable item I1
         */
        final InvoiceItem taxableItem1 = TestUtils.buildInvoiceItem(invoice1, InvoiceItemType.RECURRING, new BigDecimal("100"), null);
        invoiceItems1.add(taxableItem1);
        pluginProperties.add(new PluginProperty(String.format("%s_%s", AvaTaxTaxCalculator.TAX_CODE, taxableItem1.getId()), "D9999999", false));
        List<InvoiceItem> additionalInvoiceItems = avaTaxInvoicePluginApi.getAdditionalInvoiceItems(invoice1, false, pluginProperties, callContext);
        // 4 TAX items expected (total $8.63)
        checkTaxes(additionalInvoiceItems, 4, new BigDecimal("8.63"));

        /*
         * Scenario 1B: re-invoice of 1A (should be idempotent)
         *     $100    Taxable item I1
         *       $8.63 Tax items (x4)
         */
        invoiceItems1.addAll(additionalInvoiceItems);
        additionalInvoiceItems = avaTaxInvoicePluginApi.getAdditionalInvoiceItems(invoice1, false, pluginProperties, callContext);
        checkTaxes(additionalInvoiceItems, 0, BigDecimal.ZERO);

        /*
         * Scenario 2A: repair on new invoice (CBA_ADJ on both invoices are omitted)
         *     -$50   Repair I2 (points to I1 on previous invoice)
         */
        final Invoice invoice2 = TestUtils.buildInvoice(account);
        final List<InvoiceItem> invoiceItems2 = new LinkedList<InvoiceItem>();
        Mockito.when(invoice2.getInvoiceItems()).thenReturn(invoiceItems2);
        final InvoiceItem repair2 = TestUtils.buildInvoiceItem(invoice2, InvoiceItemType.REPAIR_ADJ, new BigDecimal("-50"), taxableItem1.getId());
        invoiceItems2.add(repair2);
        Mockito.when(osgiKillbillAPI.getInvoiceUserApi().getInvoiceByInvoiceItem(Mockito.eq(taxableItem1.getId()), Mockito.<TenantContext>any()))
               .thenReturn(invoice1);
        additionalInvoiceItems = avaTaxInvoicePluginApi.getAdditionalInvoiceItems(invoice2, false, pluginProperties, callContext);
        // 4 TAX items expected (total -$4.32)
        checkTaxes(additionalInvoiceItems, 4, new BigDecimal("-4.32"));

        /*
         * Scenario 2B: re-invoice of 2A (should be idempotent)
         *     -$50    Repair I2 (points to I1 on previous invoice)
         *      -$4.32 Tax items (x4)
         */
        invoiceItems2.addAll(additionalInvoiceItems);
        additionalInvoiceItems = avaTaxInvoicePluginApi.getAdditionalInvoiceItems(invoice2, false, pluginProperties, callContext);
        checkTaxes(additionalInvoiceItems, 0, BigDecimal.ZERO);
    }

    private void checkTaxes(final Collection<InvoiceItem> additionalInvoiceItems, final int nbItems, final BigDecimal totalTax) {
        Assert.assertEquals(additionalInvoiceItems.size(), nbItems);
        BigDecimal computedTax = BigDecimal.ZERO;
        for (final InvoiceItem invoiceItem : additionalInvoiceItems) {
            Assert.assertEquals(invoiceItem.getInvoiceItemType(), InvoiceItemType.TAX);
            computedTax = computedTax.add(invoiceItem.getAmount());
        }
        Assert.assertEquals(computedTax.compareTo(totalTax), 0, String.format("Computed tax: %s, Expected tax: %s", computedTax, totalTax));
    }
}
