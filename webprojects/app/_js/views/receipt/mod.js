var ReceiptModView = Fluid.compileView({
	template: templates.receipt.mod,
	/*	@param	name The name of the mod
	 *	@param	price The price of the mod in cents (need not be positive)
	 *	@param	i	The index of the mod in the list of mods of the item
	 */
	fill: function(name, price, i)
	{
		return {position: (i+1)*consts.MOD_HEIGHT,
				name: name,
				price: money.toStr(price)};
	}
});
